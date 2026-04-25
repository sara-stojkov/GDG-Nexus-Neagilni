from fastapi import APIRouter
from datetime import datetime
from models.schemas import (
    SymptomLogRequest, SymptomLogResponse,
    MedicationLogRequest, MucosaScoreRequest
)
from services.firebase_service import (
    get_or_create_profile,
    log_symptom_event,
    log_medication,
    get_recent_medications,
    update_threshold,
    update_sensitivity
)
from services.mucosa_service import analyze_mucosa_image

router = APIRouter()


@router.post("/log", response_model=SymptomLogResponse)
async def log_symptom(request: SymptomLogRequest):
    profile = get_or_create_profile(request.user_id)
    current_threshold = profile["threshold"]

    # Check if the medicine has been taken in the last 2 hours
    medication_taken = request.medication_taken_last_2h
    if not medication_taken:
        recent_meds = get_recent_medications(request.user_id, hours=2)
        medication_taken = len(recent_meds) > 0

    # Log event in Firebase
    log_symptom_event(request.user_id, {
        "type": request.type,
        "count": request.count,
        "lat": request.lat,
        "lng": request.lng,
        "pollen_score": request.pollen_score,
        "dominant_allergen": request.dominant_allergen,
        "medication_taken_last_2h": medication_taken,
        "mucosa_score": request.mucosa_score,
        "timestamp": request.timestamp.isoformat() if request.timestamp else datetime.now().isoformat()
    })

    # Threshold adaptation
    new_threshold = current_threshold
    sensitivity_updated = False

    if request.count >= 3:
        # Strong attack — lower the threshold and increase the sensitivity
        new_threshold = max(20, current_threshold - 5)
        if request.dominant_allergen:
            update_sensitivity(request.user_id, request.dominant_allergen, delta=0.1)
            sensitivity_updated = True
    elif request.count >= 1:
        # Mild attack
        new_threshold = max(20, current_threshold - 2)
        if request.dominant_allergen:
            update_sensitivity(request.user_id, request.dominant_allergen, delta=0.05)
            sensitivity_updated = True

    # If the medicine is taken and the symptoms are still present — the threshold may be too high
    if medication_taken and request.count >= 2:
        new_threshold = max(20, new_threshold - 3)

    if new_threshold != current_threshold:
        update_threshold(request.user_id, new_threshold)

    return SymptomLogResponse(
        allergy_id_updated=new_threshold != current_threshold,
        new_threshold=new_threshold,
        sensitivity_updated=sensitivity_updated
    )


@router.post("/medication")
async def log_medication_intake(request: MedicationLogRequest):
    log_medication(request.user_id, {
        "name": request.name,
        "dose_mg": request.dose_mg,
    })
    return {"logged": True, "medication": request.name}


@router.post("/mucosa")
async def log_mucosa(request: MucosaScoreRequest):
    result = await analyze_mucosa_image(
        user_id=request.user_id,
        image_data=request.image_data
    )
    return {
        "logged": True,
        "score": result["score"],
        "interpretation": result["interpretation"],
        "source": result["source"]
    }
