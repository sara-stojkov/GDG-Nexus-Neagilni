import httpx
import math
import os
from dotenv import load_dotenv

load_dotenv()

POLLEN_API_KEY = os.getenv("POLLEN_API_KEY")
POLLEN_API_URL = "https://pollen.googleapis.com/v1/forecast:lookup"

ALLERGEN_MAP = {
    "BIRCH": "birch",
    "GRASS": "grass",
    "WEED": "weed",
    "OAK": "oak",
    "PINE": "pine",
}


def get_lookahead_points(lat: float, lng: float, heading: float, count: int = 4) -> list[tuple]:
    """
    Generates points in the direction of movement at distances 500m, 1km, 1.5km, 2km.
    """
    points = [(lat, lng)]  # current location always first
    distances = [500, 1000, 1500, 2000]  # meters

    heading_rad = math.radians(heading)
    R = 6371000  # radius in meters

    for d in distances[:count]:
        lat_rad = math.radians(lat)
        lng_rad = math.radians(lng)

        new_lat_rad = math.asin(
            math.sin(lat_rad) * math.cos(d / R) +
            math.cos(lat_rad) * math.sin(d / R) * math.cos(heading_rad)
        )
        new_lng_rad = lng_rad + math.atan2(
            math.sin(heading_rad) * math.sin(d / R) * math.cos(lat_rad),
            math.cos(d / R) - math.sin(lat_rad) * math.sin(new_lat_rad)
        )

        points.append((math.degrees(new_lat_rad), math.degrees(new_lng_rad)))

    return points


def score_to_risk(score: int) -> str:
    if score <= 30:
        return "low"
    elif score <= 65:
        return "medium"
    else:
        return "high"


async def get_pollen_data(lat: float, lng: float) -> dict:
    params = {
        "key": POLLEN_API_KEY,
        "location.latitude": lat,
        "location.longitude": lng,
        "days": 1,
    }

    async with httpx.AsyncClient() as client:
        response = await client.get(POLLEN_API_URL, params=params)

    if response.status_code != 200:
        return _mock_pollen_response()

    data = response.json()

    try:
        daily = data["dailyInfo"][0]
        plant_info = daily.get("plantInfo", [])

        species = {}
        for plant in plant_info:
            name = plant.get("plant", {}).get("name", "").upper()
            allergen = ALLERGEN_MAP.get(name)
            if allergen:
                species[allergen] = plant.get("indexInfo", {}).get("value", 0)

        return {"species": species}

    except (KeyError, IndexError, ValueError):
        return _mock_pollen_response()


def _mock_pollen_response() -> dict:
    return {
        "species": {
            "birch": 3,
            "grass": 1,
            "weed": 0,
            "oak": 2,
            "pine": 1,
        }
    }


