from pydantic import BaseModel
from typing import List, Optional
from datetime import datetime


# --- Pollen / Route ---

class PollenRiskRequest(BaseModel):
    lat: float
    lng: float
    user_id: str


class PollenRiskResponse(BaseModel):
    risk_level: str        # "low" | "medium" | "high"
    dominant_allergen: str
    score: int             # 0-100
    advice: str


class RouteWaypoint(BaseModel):
    lat: float
    lng: float


class RouteRiskRequest(BaseModel):
    user_id: str
    waypoints: List[RouteWaypoint]


class RouteSegment(BaseModel):
    from_point: List[float]
    to_point: List[float]
    risk: str              # "low" | "medium" | "high"


class RouteRiskResponse(BaseModel):
    segments: List[RouteSegment]


# --- Symptoms ---

class SymptomLogRequest(BaseModel):
    user_id: str
    type: str              # "sneeze" | "cough"
    count: int
    lat: float
    lng: float
    timestamp: Optional[datetime] = None


class SymptomLogResponse(BaseModel):
    allergy_id_updated: bool
    new_threshold: int


# --- Profile ---

class AllergyProfileResponse(BaseModel):
    user_id: str
    allergens: List[str]
    threshold: int
    peak_hours: List[str]
