from fastapi import APIRouter, HTTPException
from models.schemas import AllergyProfileResponse
from services.firebase_service import (
    get_or_create_profile,
    update_profile,
    get_symptom_pollen_correlation,
    get_historical_hotspots,
    get_recent_symptom_events,
    get_recent_medications,
)
from services.mucosa_service import get_mucosa_context

router = APIRouter()


@router.get("/allergy-profile", response_model=AllergyProfileResponse)
async def get_allergy_profile(user_id: str):
    profile = get_or_create_profile(user_id)

    return AllergyProfileResponse(
        user_id=profile["user_id"],
        allergens=profile["allergens"],
        threshold=profile["threshold"],
        peak_hours=profile["peak_hours"],
        symptom_sensitivity=profile.get("symptom_sensitivity", {})
    )


@router.get("/full-context")
async def get_full_context(user_id: str):
    """
    Complete user state snapshot —
    useful for android to display detailed profile.
    """
    profile = get_or_create_profile(user_id)
    correlation = get_symptom_pollen_correlation(user_id)
    hotspots = get_historical_hotspots(user_id)
    recent_symptoms = get_recent_symptom_events(user_id, hours=48)
    recent_medications = get_recent_medications(user_id, hours=12)
    mucosa = await get_mucosa_context(user_id)

    total_sneezes = sum(
        e.get("count", 0) for e in recent_symptoms if e.get("type") == "sneeze"
    )
    total_coughs = sum(
        e.get("count", 0) for e in recent_symptoms if e.get("type") == "cough"
    )

    return {
        "profile": profile,
        "stats": {
            "sneezes_48h": total_sneezes,
            "coughs_48h": total_coughs,
            "symptom_events_48h": len(recent_symptoms),
            "medications_12h": len(recent_medications),
            "hotspots_total": len(hotspots)
        },
        "correlation": correlation,
        "mucosa": mucosa,
        "hotspots": hotspots[:5]
    }


@router.put("/allergy-profile/{user_id}/allergens")
async def update_allergens(user_id: str, allergens: list[str]):
    if not allergens:
        raise HTTPException(status_code=400, detail="The list of allergens cannot be empty.")

    update_profile(user_id, {"allergens": allergens})
    return {"updated": True, "allergens": allergens}


@router.put("/allergy-profile/{user_id}/threshold")
async def update_threshold_manually(user_id: str, threshold: int):
    if not (0 <= threshold <= 100):
        raise HTTPException(status_code=400, detail="Threshold must be between 0 i 100.")

    update_profile(user_id, {"threshold": threshold})
    return {"updated": True, "threshold": threshold}


@router.put("/allergy-profile/{user_id}/peak-hours")
async def update_peak_hours(user_id: str, peak_hours: list[str]):
    update_profile(user_id, {"peak_hours": peak_hours})
    return {"updated": True, "peak_hours": peak_hours}


@router.delete("/allergy-profile/{user_id}/reset-sensitivity")
async def reset_sensitivity(user_id: str):
    """
    Reset sensitivity coefficients to default 1.0.
    Useful if the user thinks the estimates are wrong.
    """
    default_sensitivity = {
        "birch": 1.0,
        "grass": 1.0,
        "weed": 1.0,
        "oak": 1.0,
        "pine": 1.0
    }
    update_profile(user_id, {"symptom_sensitivity": default_sensitivity})
    return {"reset": True, "sensitivity": default_sensitivity}
