from fastapi import APIRouter, HTTPException
from models.schemas import AllergyProfileResponse
from store import get_profile, profiles

router = APIRouter()


@router.get("/allergy-profile", response_model=AllergyProfileResponse)
async def get_allergy_profile(user_id: str):
    profile = get_profile(user_id)

    return AllergyProfileResponse(
        user_id=profile["user_id"],
        allergens=profile["allergens"],
        threshold=profile["threshold"],
        peak_hours=profile["peak_hours"]
    )


@router.put("/allergy-profile/{user_id}/allergens")
async def update_allergens(user_id: str, allergens: list[str]):
    if user_id not in profiles:
        raise HTTPException(status_code=404, detail="User not found.")

    profiles[user_id]["allergens"] = allergens
    return {"updated": True, "allergens": allergens}


@router.put("/allergy-profile/{user_id}/threshold")
async def update_threshold_manually(user_id: str, threshold: int):
    if user_id not in profiles:
        raise HTTPException(status_code=404, detail="User not found.")

    if not (0 <= threshold <= 100):
        raise HTTPException(status_code=400, detail="Threshold must between 0 and 100.")

    profiles[user_id]["threshold"] = threshold
    return {"updated": True, "threshold": threshold}
