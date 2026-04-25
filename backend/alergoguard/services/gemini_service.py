import vertexai
from vertexai.generative_models import GenerativeModel
from google.oauth2 import service_account
import asyncio
import os
import logging
from dotenv import load_dotenv
from services.firebase_service import (
    get_recent_symptom_events,
    get_symptom_pollen_correlation,
    get_recent_medications,
    get_historical_hotspots,
    get_or_create_profile
)
from services.mucosa_service import get_mucosa_context

load_dotenv()

logger = logging.getLogger(__name__)

SERVICE_ACCOUNT_FILE = os.getenv("GEMINI_CREDENTIALS", "gemini_credentials.json")

creds = service_account.Credentials.from_service_account_file(SERVICE_ACCOUNT_FILE)

vertexai.init(
    project="hackathongdgnexus",
    location="europe-west1",
    credentials=creds,
)

model = GenerativeModel("gemini-2.5-flash")


async def build_user_context(
    user_id: str,
    current_pollen: dict,
    profile: dict = None,
    recent_events: list = None
) -> dict:
    """
    Gathers all available user data for the Gemini context.
    Accepts pre-fetched profile and recent_events to avoid duplicate Firebase reads.
    Remaining calls run in parallel in a thread pool.
    """
    if profile is None or recent_events is None:
        # Fallback: fetch from Firebase if not provided
        profile_task = asyncio.to_thread(get_or_create_profile, user_id)
        events_task = asyncio.to_thread(get_recent_symptom_events, user_id, 48)
        (
            profile,
            recent_events,
            correlation,
            recent_medications,
            hotspots,
            mucosa,
        ) = await asyncio.gather(
            profile_task,
            events_task,
            asyncio.to_thread(get_symptom_pollen_correlation, user_id),
            asyncio.to_thread(get_recent_medications, user_id, 12),
            asyncio.to_thread(get_historical_hotspots, user_id),
            get_mucosa_context(user_id),
        )
    else:
        # Profile and events already fetched — only run remaining calls
        (
            correlation,
            recent_medications,
            hotspots,
            mucosa,
        ) = await asyncio.gather(
            asyncio.to_thread(get_symptom_pollen_correlation, user_id),
            asyncio.to_thread(get_recent_medications, user_id, 12),
            asyncio.to_thread(get_historical_hotspots, user_id),
            get_mucosa_context(user_id),
        )

    # Fix: each event is one symptom occurrence, not a count aggregate
    total_sneezes = sum(1 for e in recent_events if e.get("type") == "sneeze")
    total_coughs = sum(1 for e in recent_events if e.get("type") == "cough")

    dominant = current_pollen.get("dominant_allergen", "grass")
    sensitivity = profile.get("symptom_sensitivity", {}).get(dominant, 1.0)
    effective_score = min(100, round(current_pollen.get("score", 0) * sensitivity))

    last_medication = recent_medications[0] if recent_medications else None
    medication_taken_last_2h = False
    if last_medication:
        from datetime import datetime, timezone, timedelta
        logged_at = last_medication.get("logged_at")
        if isinstance(logged_at, str):
            logged_at = datetime.fromisoformat(logged_at)
        if logged_at:
            if logged_at.tzinfo is None:
                logged_at = logged_at.replace(tzinfo=timezone.utc)
            medication_taken_last_2h = (datetime.now(timezone.utc) - logged_at) < timedelta(hours=2)

    return {
        "profile": profile,
        "current_pollen": current_pollen,
        "effective_score": effective_score,
        "sensitivity": sensitivity,
        "recent_symptoms": {
            "sneezes_48h": total_sneezes,
            "coughs_48h": total_coughs,
            "total_events": len(recent_events)
        },
        "correlation": correlation,
        "mucosa": mucosa,
        "medication": {
            "last": last_medication,
            "taken_last_2h": medication_taken_last_2h,
            "total_last_12h": len(recent_medications)
        },
        "hotspots_count": len(hotspots)
    }


def build_prompt(ctx: dict, hour: int) -> str:
    p = ctx["profile"]
    pollen = ctx["current_pollen"]
    symptoms = ctx["recent_symptoms"]
    mucosa = ctx["mucosa"]
    med = ctx["medication"]
    corr = ctx["correlation"]

    corr_text = "insufficient data"
    if corr.get("correlation") == "available":
        corr_text = f"symptoms typically occur at score {corr['avg_trigger_score']}"

    med_text = "not taken"
    if med["taken_last_2h"]:
        med_text = f"taken less than 2h ({med['last']['name']})"
    elif med["last"]:
        med_text = f"last medicine: {med['last']['name']}, more than 2 hours ago"

    mucosa_text = "no data"
    if mucosa["latest_score"] is not None:
        mucosa_text = (
            f"score {mucosa['latest_score']}/100 "
            f"({mucosa['interpretation']}), "
            f"trend: {mucosa['trend']}"
        )

    return f"""
You are a medical assistant for allergic drivers. Analyze the situation and give concrete advice.

USER PROFILE:
- Allergic to: {', '.join(p.get('allergens', []))}
- Personal tolerance threshold: {p.get('threshold', 65)}/100
- Sensitivity to {pollen['dominant_allergen']}: {ctx['sensitivity']}x (1.0 = average)

CURRENT STATE OF POLLEN:
- Dominant allergen: {pollen['dominant_allergen']}
- Pollen score: {pollen['score']}/100
- Effective score (corrected by sensitivity): {ctx['effective_score']}/100
- Risk level: {pollen['risk_level']}
- Hour: {hour}h

SYMPTOMS (last 48 hours):
- Total sneezes: {symptoms['sneezes_48h']}
- Total coughs: {symptoms['coughs_48h']}
- Number events: {symptoms['total_events']}
- Historical correlation: {corr_text}

CONDITION OF MUCOSA:
- {mucosa_text}

THERAPY:
- {med_text}
- Total medications in the last 12h: {med['total_last_12h']}

HISTORICAL HOTSPOTS:
- User had {ctx['hotspots_count']} alarms in previous locations

ASSIGNMENT:
Give two outputs:
1. TIP (max 15 words, specific for the driver)
2. RISK (max 25 words, why this risk was determined)

Answer format — exclusively like this, without additional text:
TIP: <text>
RISK: <text>
"""


async def get_driving_advice(
    allergens: list[str],
    score: int,
    threshold: int,
    risk_level: str,
    dominant_allergen: str,
    hour: int,
    user_id: str = None,
    profile: dict = None,
    recent_events: list = None
) -> dict:
    """
    If user_id is available — use the full context.
    Accepts pre-fetched profile and recent_events to avoid duplicate Firebase reads.
    """
    current_pollen = {
        "score": score,
        "risk_level": risk_level,
        "dominant_allergen": dominant_allergen
    }

    try:
        if user_id:
            ctx = await build_user_context(
                user_id,
                current_pollen,
                profile=profile,
                recent_events=recent_events
            )
            prompt = build_prompt(ctx, hour)
        else:
            prompt = _simple_prompt(allergens, score, threshold, risk_level, dominant_allergen, hour)

        response = await asyncio.to_thread(
            model.generate_content,
            prompt
        )
        return _parse_response(response.text.strip(), risk_level)

    except Exception as e:
        logger.error("Gemini error: %s", e)
        return _fallback_response(risk_level)


def _parse_response(text: str, risk_level: str) -> dict:
    lines = text.strip().split("\n")
    advice = None
    explanation = None

    for line in lines:
        if line.startswith("TIP:"):
            advice = line.replace("TIP:", "").strip()
        elif line.startswith("RISK:"):
            explanation = line.replace("RISK:", "").strip()

    if not advice:
        logger.warning("Gemini response is not parsed correctly, I use fallback. Text: %s", text[:200])
        return _fallback_response(risk_level)

    return {
        "advice": advice,
        "risk_explanation": explanation or ""
    }


def _simple_prompt(allergens, score, threshold, risk_level, dominant_allergen, hour) -> str:
    return f"""
User is allergic to: {', '.join(allergens)}
Current pollen score: {score}/100, threshold: {threshold}/100
Dominant allergen: {dominant_allergen}, risk: {risk_level}, hour: {hour}h

Give two outputs:
TIP: <max 15 words, specific for the driver>
RISK: <max 25 words, explanation>
"""


def _fallback_response(risk_level: str) -> dict:
    fallbacks = {
        "low": {
            "advice": "Conditions are favorable, driving is safe.",
            "risk_explanation": "The pollen level is below your tolerance threshold."
        },
        "medium": {
            "advice": "Close the windows and watch for symptoms while driving.",
            "risk_explanation": "Moderate pollen level — possible mild reaction."
        },
        "high": {
            "advice": "Take your medicine before leaving and close the windows.",
            "risk_explanation": "High pollen levels exceed your personal tolerance threshold."
        },
    }
    return fallbacks.get(risk_level, fallbacks["medium"])
