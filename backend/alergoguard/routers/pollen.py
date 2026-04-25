from fastapi import APIRouter
from datetime import datetime
from models.schemas import (
    PollenRiskResponse,
    LocationUpdateRequest, LocationUpdateResponse, HardwareSignals
)
from services.pollen_service import get_pollen_data, get_lookahead_points
from services.gemini_service import get_driving_advice
from services.firebase_service import (
    get_or_create_profile,
    log_location_event,
    update_sensitivity
)

router = APIRouter()


def build_hardware_signals(risk_level: str) -> HardwareSignals:
    return HardwareSignals(
        close_windows=risk_level in ["medium", "high"],
        activate_cabin_filter=risk_level in ["medium", "high"],
        reduce_speed=risk_level == "high",
        alert_driver=risk_level == "high"
    )


@router.post("/location-update", response_model=LocationUpdateResponse)
async def location_update(request: LocationUpdateRequest):
    profile = get_or_create_profile(request.user_id)

    # Current location + lookahead points
    points = get_lookahead_points(request.lat, request.lng, request.heading)

    # Pollen for each point
    results = []
    for lat, lng in points:
        data = await get_pollen_data(lat, lng)
        results.append(data)

    # Worst score
    worst = max(results, key=lambda x: x["score"])
    lookahead_worst = max(results[1:], key=lambda x: x["score"]) if len(results) > 1 else worst

    # Gemini with full context
    gemini_response = await get_driving_advice(
        allergens=profile["allergens"],
        score=worst["score"],
        threshold=profile["threshold"],
        risk_level=worst["risk_level"],
        dominant_allergen=worst["dominant_allergen"],
        hour=datetime.now().hour,
        user_id=request.user_id
    )

    triggered_alarm = worst["risk_level"] == "high"

    # Log location to Firebase
    log_location_event(request.user_id, {
        "lat": request.lat,
        "lng": request.lng,
        "heading": request.heading,
        "speed": request.speed,
        "pollen_score": worst["score"],
        "dominant_allergen": worst["dominant_allergen"],
        "risk_level": worst["risk_level"],
        "triggered_alarm": triggered_alarm
    })

    # If it is an alarm — increase the sensitivity for the dominant allergen
    if triggered_alarm:
        update_sensitivity(request.user_id, worst["dominant_allergen"], delta=0.05)

    return LocationUpdateResponse(
        risk_score=worst["score"],
        risk_level=worst["risk_level"],
        dominant_allergen=worst["dominant_allergen"],
        advice=gemini_response["advice"],
        risk_explanation=gemini_response["risk_explanation"],
        hardware_signals=build_hardware_signals(worst["risk_level"]),
        lookahead_risk=lookahead_worst["risk_level"]
    )


@router.get("/risk", response_model=PollenRiskResponse)
async def get_pollen_risk(lat: float, lng: float, user_id: str):
    profile = get_or_create_profile(user_id)
    pollen = await get_pollen_data(lat, lng)

    gemini_response = await get_driving_advice(
        allergens=profile["allergens"],
        score=pollen["score"],
        threshold=profile["threshold"],
        risk_level=pollen["risk_level"],
        dominant_allergen=pollen["dominant_allergen"],
        hour=datetime.now().hour,
        user_id=user_id
    )

    return PollenRiskResponse(
        risk_level=pollen["risk_level"],
        dominant_allergen=pollen["dominant_allergen"],
        score=pollen["score"],
        advice=gemini_response["advice"]
    )
