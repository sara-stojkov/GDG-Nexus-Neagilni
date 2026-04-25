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
from datetime import datetime

router = APIRouter()


class YamNetEventRequest(BaseModel):
    user_id: str
    event_type: str  # "sneeze" | "cough" | "throat_clear"
    confidence: float  # 0.0 - 1.0, the score returned by YAMNet
    count: int  # number of times detected in the last 60s
    lat: float
    lng: float


class YamNetEventResponse(BaseModel):
    alarm: bool
    alarm_level: str       # "none" | "warning" | "critical"
    advice: str
    risk_explanation: str
    hardware_signals: dict
    threshold_updated: bool
    new_threshold: int


@router.post("/event", response_model=YamNetEventResponse)
async def yamnet_event(request: YamNetEventRequest):
    profile = get_or_create_profile(request.user_id)
    current_threshold = profile["threshold"]

    # Current pollen at the location
    pollen = await get_pollen_data(request.lat, request.lng)

    # Log as symptom event
    log_symptom_event(request.user_id, {
        "type": request.event_type,
        "count": request.count,
        "lat": request.lat,
        "lng": request.lng,
        "pollen_score": pollen["score"],
        "dominant_allergen": pollen["dominant_allergen"],
        "yamnet_confidence": request.confidence,
        "source": "yamnet"
    })

    # Determine the alarm level based on the combination:
    # - frequencies (count)
    # - YAMNet confidence
    # - current pollen score
    alarm_level = _calculate_alarm_level(
        count=request.count,
        confidence=request.confidence,
        pollen_score=pollen["score"],
        threshold=current_threshold
    )
    alarm = alarm_level in ["warning", "critical"]

    # Threshold adaptation
    new_threshold = current_threshold
    if alarm and request.count >= 3:
        new_threshold = max(20, current_threshold - 5)
        update_threshold(request.user_id, new_threshold)
        if pollen["dominant_allergen"]:
            update_sensitivity(request.user_id, pollen["dominant_allergen"], delta=0.1)
    elif alarm and request.count >= 1:
        new_threshold = max(20, current_threshold - 2)
        update_threshold(request.user_id, new_threshold)

    # Geminis advice with full context
    gemini_response = await get_driving_advice(
        allergens=profile["allergens"],
        score=pollen["score"],
        threshold=new_threshold,
        risk_level=pollen["risk_level"],
        dominant_allergen=pollen["dominant_allergen"],
        hour=datetime.now().hour,
        user_id=request.user_id
    )

    # Hardware signals — more aggressive when YAMNet detects symptoms
    hardware_signals = _build_yamnet_hardware_signals(alarm_level)

    return YamNetEventResponse(
        alarm=alarm,
        alarm_level=alarm_level,
        advice=gemini_response["advice"],
        risk_explanation=gemini_response["risk_explanation"],
        hardware_signals=hardware_signals,
        threshold_updated=new_threshold != current_threshold,
        new_threshold=new_threshold
    )


@router.get("/recent-events")
async def get_recent_yamnet_events(user_id: str, hours: int = 24):
    """
    Returns YAMNet events from Firebase —
    useful for Android to display attack history.
    """
    all_events = get_recent_symptom_events(user_id, hours=hours)
    yamnet_events = [e for e in all_events if e.get("source") == "yamnet"]

    return {
        "events": yamnet_events,
        "total": len(yamnet_events),
        "sneezes": len([e for e in yamnet_events if e.get("type") == "sneeze"]),
        "coughs": len([e for e in yamnet_events if e.get("type") == "cough"])
    }


# ─── HELPERS ─────────────────────────────────────────────────────

def _calculate_alarm_level(
    count: int,
    confidence: float,
    pollen_score: int,
    threshold: int
) -> str:
    # Combined score — we take all factors into account
    combined = (count * 20) + (confidence * 30) + (pollen_score * 0.5)

    # If pollen is below threshold — milder alarm even if YAMNet detects
    pollen_factor = pollen_score / threshold if threshold > 0 else 1.0

    adjusted = combined * min(pollen_factor, 1.5)

    if adjusted < 30:
        return "none"
    elif adjusted < 60:
        return "warning"
    else:
        return "critical"


def _build_yamnet_hardware_signals(alarm_level: str) -> dict:
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
