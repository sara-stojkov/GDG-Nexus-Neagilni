from services.firebase_service import (
    log_mucosa_score,
    get_recent_mucosa_scores,
    get_mucosa_trend
)


# ─── MOCK EVALUATION ───────────────────────────────────────────────
# Hook for future integration with the vision model.
# When the model is ready, just replace _mock_analyze_image
# with the right call to Gemini Vision or custom model.

async def analyze_mucosa_image(user_id: str, image_data: str = None) -> dict:
    """
    image_data: base64 image string (optional for now)
    Currently it always returns mock score.
    When the right model arrives:
    score = await vision_model.analyze(image_data)
    """
    score = _mock_analyze_image(image_data)

    log_mucosa_score(
        user_id=user_id,
        score=score,
        source="mock",
        metadata={
            "model_version": "mock_v1",
            "image_provided": image_data is not None
        }
    )

    return {
        "score": score,
        "source": "mock",
        "interpretation": _interpret_score(score)
    }


async def get_mucosa_context(user_id: str) -> dict:
    """
    The main interface to the rest of the system.
    It returns everything Gemini needs to know about the condition of the mucosa.
    """
    trend = get_mucosa_trend(user_id)
    recent = get_recent_mucosa_scores(user_id, hours=24)

    return {
        "latest_score": trend.get("latest_score"),
        "trend": trend.get("trend"),
        "delta": trend.get("delta"),
        "interpretation": _interpret_score(trend.get("latest_score")),
        "data_points_24h": len(recent)
    }


# ─── HELPERS ─────────────────────────────────────────────────────

def _mock_analyze_image(image_data: str = None) -> int:
    """
    Mock score 0-100.
    0 = healthy mucosa, 100 = very inflamed.
    """
    import random
    return random.randint(30, 70)


def _interpret_score(score: int | None) -> str:
    if score is None:
        return "no_data"
    if score <= 25:
        return "healthy"
    elif score <= 50:
        return "mild_inflammation"
    elif score <= 75:
        return "moderate_inflammation"
    else:
        return "severe_inflammation"
