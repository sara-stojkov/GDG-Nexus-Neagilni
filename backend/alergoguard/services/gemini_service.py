import google.generativeai as genai
import os
from dotenv import load_dotenv

load_dotenv()

genai.configure(api_key=os.getenv("GEMINI_API_KEY"))
model = genai.GenerativeModel("gemini-1.5-flash")


async def get_driving_advice(
    allergens: list[str],
    score: int,
    threshold: int,
    risk_level: str,
    dominant_allergen: str,
    hour: int
) -> str:
    prompt = f"""
    You are an assistant for allergic drivers. Give one specific advice for driving.
    
    User is allergic to: {', '.join(allergens)}
    Dominant allergen currently: {dominant_allergen}
    Current pollen score: {score}/100
    User's personal tolerance threshold: {threshold}/100
    Risk level: {risk_level}
    Hour: {hour}h
    
    Rules:
    - Maximum 15 words
    - Specific advice (e.g. take medicine, close windows, avoid the park)
    - If the score is below the threshold, say it's safe
    - No introductory phrases like "Tip:" or "Recommendation:"
    """

    try:
        response = model.generate_content(prompt)
        return response.text.strip()
    except Exception:
        return _fallback_advice(risk_level)


def _fallback_advice(risk_level: str) -> str:
    fallbacks = {
        "low": "Conditions are favorable, driving is safe.",
        "medium": "Moderate pollen levels — close windows while driving.",
        "high": "High pollen level — take medicine before departure.",
    }

    return fallbacks.get(risk_level, "Caution while driving.")
