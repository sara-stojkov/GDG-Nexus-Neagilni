from pydantic import BaseModel
from typing import List, Optional
from datetime import datetime


class PollenRiskRequest(BaseModel):
    lat: float
    lng: float
    user_id: str


class PollenRiskResponse(BaseModel):
    risk_level: str
    dominant_allergen: str
    score: int
    advice: str


class RouteWaypoint(BaseModel):
    lat: float
    lng: float


class LocationUpdateRequest(BaseModel):
    user_id: str
    lat: float
    lng: float
    heading: float = 0.0
    speed: Optional[float] = None


class HardwareSignals(BaseModel):
    close_windows: bool
    activate_cabin_filter: bool
    reduce_speed: bool
    alert_driver: bool


class LocationUpdateResponse(BaseModel):
    risk_score: int
    risk_level: str
    dominant_allergen: str
    advice: str
    risk_explanation: str
    hardware_signals: HardwareSignals
    lookahead_risk: str


class SymptomLogRequest(BaseModel):
    user_id: str
    type: str
    count: int
    lat: float
    lng: float
    pollen_score: Optional[int] = None
    dominant_allergen: Optional[str] = None
    medication_taken_last_2h: Optional[bool] = False
    mucosa_score: Optional[int] = None
    timestamp: Optional[datetime] = None


class SymptomLogResponse(BaseModel):
    allergy_id_updated: bool
    new_threshold: int
    sensitivity_updated: bool


class MedicationLogRequest(BaseModel):
    user_id: str
    name: str
    dose_mg: Optional[int] = None


class MucosaScoreRequest(BaseModel):
    user_id: str
    image_data: Optional[str] = None


class AllergyProfileResponse(BaseModel):
    user_id: str
    allergens: List[str]
    threshold: int
    peak_hours: List[str]
    symptom_sensitivity: dict
