import httpx
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
        # Fallback mock if API doesn't work in that area
        return _mock_pollen_response()

    data = response.json()

    try:
        daily = data["dailyInfo"][0]
        plant_info = daily.get("plantInfo", [])

        # find dominant allergen by indexValue
        dominant = max(plant_info, key=lambda x: x.get("indexInfo", {}).get("value", 0))
        score = dominant.get("indexInfo", {}).get("value", 0) * 20  # 0-5 → 0-100
        name = dominant.get("plant", {}).get("name", "GRASS")
        allergen = ALLERGEN_MAP.get(name.upper(), name.lower())

        return {
            "score": min(score, 100),
            "dominant_allergen": allergen,
            "risk_level": score_to_risk(score),
        }

    except (KeyError, IndexError, ValueError):
        return _mock_pollen_response()


def _mock_pollen_response() -> dict:
    return {
        "score": 75,
        "dominant_allergen": "birch",
        "risk_level": "high",
    }
