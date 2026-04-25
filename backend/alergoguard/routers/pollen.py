from fastapi import APIRouter, HTTPException
from datetime import datetime
from models.schemas import (
    PollenRiskRequest, PollenRiskResponse,
    RouteRiskRequest, RouteRiskResponse, RouteSegment
)
from services.pollen_service import get_pollen_data
from services.gemini_service import get_driving_advice
from store import get_profile

router = APIRouter()


@router.get("/risk", response_model=PollenRiskResponse)
async def get_pollen_risk(lat: float, lng: float, user_id: str):
    profile = get_profile(user_id)
    pollen = await get_pollen_data(lat, lng)

    advice = await get_driving_advice(
        allergens=profile["allergens"],
        score=pollen["score"],
        threshold=profile["threshold"],
        risk_level=pollen["risk_level"],
        dominant_allergen=pollen["dominant_allergen"],
        hour=datetime.now().hour
    )

    return PollenRiskResponse(
        risk_level=pollen["risk_level"],
        dominant_allergen=pollen["dominant_allergen"],
        score=pollen["score"],
        advice=advice
    )


@router.post("/route-risk", response_model=RouteRiskResponse)
async def get_route_risk(request: RouteRiskRequest):
    if len(request.waypoints) < 2:
        raise HTTPException(status_code=400, detail="At least 2 waypoints are required.")

    segments = []

    for i in range(len(request.waypoints) - 1):
        start = request.waypoints[i]
        end = request.waypoints[i + 1]

        # Let's take the midpoint of the segment for the pollen query
        mid_lat = (start.lat + end.lat) / 2
        mid_lng = (start.lng + end.lng) / 2

        pollen = await get_pollen_data(mid_lat, mid_lng)

        segments.append(RouteSegment(
            from_point=[start.lat, start.lng],
            to_point=[end.lat, end.lng],
            risk=pollen["risk_level"]
        ))

    return RouteRiskResponse(segments=segments)
