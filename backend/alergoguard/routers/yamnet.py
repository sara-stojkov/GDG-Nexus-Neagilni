from fastapi import APIRouter
from pydantic import BaseModel
from services.firebase_service import (
    get_or_create_profile,
    log_symptom_event,
    update_threshold,
    update_sensitivity,
    get_recent_symptom_events
)
from services.gemini_service import get_driving_advice
from services.pollen_service import get_pollen_data
from datetime import datetime, timezone, timedelta
import asyncio

router = APIRouter()

# ─── THRESHOLDS ──────────────────────────────────────────────────

# Minimum confidence below which we ignore the event entirely
CONFIDENCE_MIN = 0.1

# Attack = 3+ events of the same type within the last 2 minutes
ATTACK_WINDOW_SECONDS = 120
ATTACK_EVENT_COUNT = 3


# ─── SCHEMAS ─────────────────────────────────────────────────────

class YamNetEventRequest(BaseModel):
    user_id: str
    event_type: str   # "sneeze" | "cough" | "throat_clear"
    confidence: float  # 0.0 - 1.0
    lat: float
    lng: float


class YamNetEventResponse(BaseModel):
    alarm: bool
    alarm_level: str        # "none" | "warning" | "critical"
    advice: str
    risk_explanation: str
    hardware_signals: dict
    threshold_updated: bool
    new_threshold: int


# ─── ENDPOINT ────────────────────────────────────────────────────

@router.post("/event", response_model=YamNetEventResponse)
async def yamnet_event(request: YamNetEventRequest):

    # 1. Ignore low-confidence events immediately — no Firebase, no Pollen, no Gemini
    if request.confidence < CONFIDENCE_MIN:
        return _no_alarm_response()

    # 2. Fetch profile and recent events in parallel — no Pollen API yet
    profile, recent_events = await asyncio.gather(
        asyncio.to_thread(get_or_create_profile, request.user_id),
        asyncio.to_thread(get_recent_symptom_events, request.user_id, hours=1)
    )
    current_threshold = profile["threshold"]

    # 3. Count how many events of the same type occurred in the attack window
    now = datetime.now(timezone.utc)
    attack_cutoff = now - timedelta(seconds=ATTACK_WINDOW_SECONDS)

    recent_same_type = [
        e for e in recent_events
        if e.get("type") == request.event_type
        and _parse_timestamp(e.get("timestamp")) >= attack_cutoff
    ]
    events_in_window = len(recent_same_type) + 1  # +1 for the current event

    # 4. Determine if this is an active attack based purely on frequency + confidence
    is_attack = (
        events_in_window >= ATTACK_EVENT_COUNT
        and request.confidence >= 0.75
    )

    if is_attack:
        # CRITICAL PATH — react immediately, no Pollen API, no Gemini
        new_threshold = max(20, current_threshold - 5)

        await asyncio.gather(
            asyncio.to_thread(log_symptom_event, request.user_id, {
                "type": request.event_type,
                "lat": request.lat,
                "lng": request.lng,
                "yamnet_confidence": request.confidence,
                "source": "yamnet",
                "attack": True
            }),
            asyncio.to_thread(update_threshold, request.user_id, new_threshold)
        )

        return YamNetEventResponse(
            alarm=True,
            alarm_level="critical",
            advice=_critical_advice(request.event_type),
            risk_explanation=f"Detected {events_in_window} {request.event_type} events in the last {ATTACK_WINDOW_SECONDS}s with {int(request.confidence * 100)}% confidence.",
            hardware_signals=_build_hardware_signals("critical"),
            threshold_updated=True,
            new_threshold=new_threshold
        )

    # 5. Not an attack — now fetch pollen to get full picture
    pollen = await get_pollen_data(request.lat, request.lng)

    # 6. Calculate alarm level using pollen context
    alarm_level = _calculate_alarm_level(
        confidence=request.confidence,
        events_in_window=events_in_window,
        pollen_score=pollen["score"],
        threshold=current_threshold
    )
    # Clamp to warning on non-attack path — critical is reserved for frequency-based attacks
    if alarm_level == "critical":
        alarm_level = "warning"

    alarm = alarm_level != "none"

    # 7. Update thresholds if there's any alarm
    new_threshold = current_threshold
    if alarm:
        new_threshold = max(20, current_threshold - 2)
        tasks = [asyncio.to_thread(update_threshold, request.user_id, new_threshold)]
        if pollen["dominant_allergen"]:
            tasks.append(asyncio.to_thread(
                update_sensitivity, request.user_id, pollen["dominant_allergen"], delta=0.05
            ))
        await asyncio.gather(*tasks)

    # 8. Log the event with full pollen context
    await asyncio.to_thread(log_symptom_event, request.user_id, {
        "type": request.event_type,
        "lat": request.lat,
        "lng": request.lng,
        "pollen_score": pollen["score"],
        "dominant_allergen": pollen["dominant_allergen"],
        "yamnet_confidence": request.confidence,
        "source": "yamnet",
        "attack": False
    })

    if alarm_level == "none":
        # Low signal — logged, no need to call Gemini
        return _no_alarm_response(new_threshold=current_threshold)

    # 9. Warning path — call Gemini for personalized advice
    # Pass profile and recent_events we already have to avoid duplicate Firebase reads
    gemini_response = await get_driving_advice(
        allergens=profile["allergens"],
        score=pollen["score"],
        threshold=new_threshold,
        risk_level=pollen["risk_level"],
        dominant_allergen=pollen["dominant_allergen"],
        hour=datetime.now().hour,
        user_id=request.user_id,
        profile=profile,
        recent_events=recent_events
    )

    return YamNetEventResponse(
        alarm=alarm,
        alarm_level=alarm_level,
        advice=gemini_response["advice"],
        risk_explanation=gemini_response["risk_explanation"],
        hardware_signals=_build_hardware_signals(alarm_level),
        threshold_updated=new_threshold != current_threshold,
        new_threshold=new_threshold
    )


@router.get("/recent-events")
async def get_recent_yamnet_events(user_id: str, hours: int = 24):
    """
    Returns YAMNet events from Firebase —
    useful for Android to display attack history.
    """
    all_events = await asyncio.to_thread(get_recent_symptom_events, user_id, hours)
    yamnet_events = [e for e in all_events if e.get("source") == "yamnet"]

    return {
        "events": yamnet_events,
        "total": len(yamnet_events),
        "sneezes": len([e for e in yamnet_events if e.get("type") == "sneeze"]),
        "coughs": len([e for e in yamnet_events if e.get("type") == "cough"]),
        "attacks": len([e for e in yamnet_events if e.get("attack") is True])
    }


# ─── HELPERS ─────────────────────────────────────────────────────

def _parse_timestamp(ts) -> datetime:
    """Handles both datetime objects (new records) and ISO strings (legacy)."""
    if isinstance(ts, datetime):
        return ts if ts.tzinfo else ts.replace(tzinfo=timezone.utc)
    if isinstance(ts, str):
        dt = datetime.fromisoformat(ts)
        return dt if dt.tzinfo else dt.replace(tzinfo=timezone.utc)
    return datetime.min.replace(tzinfo=timezone.utc)


def _calculate_alarm_level(
    confidence: float,
    events_in_window: int,
    pollen_score: int,
    threshold: int
) -> str:
    combined = (confidence * 40) + (events_in_window * 10) + (pollen_score * 0.3)
    pollen_factor = pollen_score / threshold if threshold > 0 else 1.0
    adjusted = combined * min(pollen_factor, 1.5)

    if adjusted < 25:
        return "none"
    elif adjusted < 55:
        return "warning"
    else:
        return "critical"


def _critical_advice(event_type: str) -> str:
    if event_type == "sneeze":
        return "Sneezing attack detected. Close windows, pull over safely if possible."
    elif event_type == "cough":
        return "Coughing attack detected. Close windows and take your medication."
    return "Symptom attack detected. Close windows and reduce speed."


def _build_hardware_signals(alarm_level: str) -> dict:
    if alarm_level == "none":
        return {
            "close_windows": False,
            "activate_cabin_filter": False,
            "reduce_speed": False,
            "alert_driver": False
        }
    elif alarm_level == "warning":
        return {
            "close_windows": True,
            "activate_cabin_filter": True,
            "reduce_speed": False,
            "alert_driver": True
        }
    else:  # critical
        return {
            "close_windows": True,
            "activate_cabin_filter": True,
            "reduce_speed": True,
            "alert_driver": True
        }


def _no_alarm_response(new_threshold: int = 65) -> YamNetEventResponse:
    return YamNetEventResponse(
        alarm=False,
        alarm_level="none",
        advice="No significant symptoms detected. Continue driving normally.",
        risk_explanation="Confidence or frequency below alarm threshold.",
        hardware_signals=_build_hardware_signals("none"),
        threshold_updated=False,
        new_threshold=new_threshold
    )
