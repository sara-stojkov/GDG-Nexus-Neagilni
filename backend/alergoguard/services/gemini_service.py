from google import genai
from google.genai import types
import os
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

client = genai.Client(api_key=os.getenv("GEMINI_API_KEY"))


async def build_user_context(user_id: str, current_pollen: dict) -> dict:
    """
    Gathers all available user data for the Gemini context.
    """
    profile = get_or_create_profile(user_id)
    recent_symptoms = get_recent_symptom_events(user_id, hours=48)
    correlation = get_symptom_pollen_correlation(user_id)
    recent_medications = get_recent_medications(user_id, hours=12)
    mucosa = await get_mucosa_context(user_id)
    hotspots = get_historical_hotspots(user_id)

    # Frequency of symptoms in the last 48 hours
    total_sneezes = sum(
        e.get("count", 0) for e in recent_symptoms if e.get("type") == "sneeze"
    )
    total_coughs = sum(
        e.get("count", 0) for e in recent_symptoms if e.get("type") == "cough"
    )

    # Sensitivity coefficient for the current dominant allergen
    dominant = current_pollen.get("dominant_allergen", "grass")
    sensitivity = profile.get("symptom_sensitivity", {}).get(dominant, 1.0)

    # Effective score — corrected for personal sensitivity
    effective_score = min(100, round(current_pollen.get("score", 0) * sensitivity))

    # The last medicine
    last_medication = recent_medications[0] if recent_medications else None
    medication_taken_last_2h = False
    if last_medication:
        from datetime import datetime, timezone, timedelta
        logged = datetime.fromisoformat(last_medication.get("logged_at", ""))
        medication_taken_last_2h = (datetime.now(timezone.utc) - logged) < timedelta(hours=2)

    return {
        "profile": profile,
        "current_pollen": current_pollen,
        "effective_score": effective_score,
        "sensitivity": sensitivity,
        "recent_symptoms": {
            "sneezes_48h": total_sneezes,
            "coughs_48h": total_coughs,
            "total_events": len(recent_symptoms)
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

    # Correlation
    corr_text = "insufficient data"
    if corr.get("correlation") == "available":
        corr_text = f"symptoms typically occur at score-u {corr['avg_trigger_score']}"

    # Lek
    med_text = "not taken"
    if med["taken_last_2h"]:
        med_text = f"taken less than 2h ({med['last']['name']})"
    elif med["last"]:
        med_text = f"last medicine: {med['last']['name']}, more than 2 hours ago"

    # Mucosa
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

CONDITION OF MUCOSUM:
- {mucosa_text}

THERAPY:
- {med_text}
- Total medications in the last 12h: {med['total_last_12h']}

HISTORICAL HOTSPOTS:
- User had {ctx['hotspots_count']} alarms in previous locations

ASSIGNMENT:
Give two outputs:
1. ADVICE (max 15 words, specific for the driver)
2. RISK_EXPLANATION (max 25 words, why this risk was determined)

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
    user_id: str = None
) -> dict:
    """
    If user_id is available — use the full context.
    If not — fallback to a simple prompt.
    """
    current_pollen = {
        "score": score,
        "risk_level": risk_level,
        "dominant_allergen": dominant_allergen
    }

    try:
        if user_id:
            ctx = await build_user_context(user_id, current_pollen)
            prompt = build_prompt(ctx, hour)
        else:
            prompt = _simple_prompt(allergens, score, threshold, risk_level, dominant_allergen, hour)

        response = client.models.generate_content(
            model="gemini-2.0-flash",
            contents=prompt
        )
        return _parse_response(response.text.strip(), risk_level)

    except Exception as e:
        print(f"Gemini error: {e}")
        return _fallback_response(risk_level)


def _parse_response(text: str, risk_level: str) -> dict:
    lines = text.strip().split("\n")
    advice = None
    explanation = None

    for line in lines:
        if line.startswith("ADVICE:"):
            advice = line.replace("ADVICE:", "").strip()
        elif line.startswith("RISK:"):
            explanation = line.replace("RISK:", "").strip()

    if not advice:
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
ADVICE: <max 15 words, specific for the driver>
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
