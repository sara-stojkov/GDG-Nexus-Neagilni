from fastapi import APIRouter
from datetime import datetime
from models.schemas import SymptomLogRequest, SymptomLogResponse
from store import get_profile, log_symptom, update_threshold

router = APIRouter()


@router.post("/log", response_model=SymptomLogResponse)
async def log_symptom_event(request: SymptomLogRequest):
    profile = get_profile(request.user_id)

    symptom = {
        "type": request.type,
        "count": request.count,
        "lat": request.lat,
        "lng": request.lng,
        "timestamp": request.timestamp or datetime.now().isoformat()
    }

    log_symptom(request.user_id, symptom)

    # Allergy ID adaptation — if the user reports symptoms,
    # and the current threshold is high, we lower it (it becomes more sensitive)
    current_threshold = profile["threshold"]
    new_threshold = current_threshold

    if request.count >= 3:
        # Strong attack — lower threshold by 5
        new_threshold = max(20, current_threshold - 5)
        update_threshold(request.user_id, new_threshold)
    elif request.count >= 1:
        # Mild attack — lower by 2
        new_threshold = max(20, current_threshold - 2)
        update_threshold(request.user_id, new_threshold)

    updated = new_threshold != current_threshold

    return SymptomLogResponse(
        allergy_id_updated=updated,
        new_threshold=new_threshold
    )
