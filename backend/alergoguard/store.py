from typing import Dict, Any

# In-memory store - for testing

profiles: Dict[str, Any] = {
    "test_user": {
        "user_id": "test_user",
        "allergens": ["birch", "grass"],
        "threshold": 65,
        "peak_hours": ["07:00", "08:00", "18:00"],
        "symptom_log": []
    }
}


def get_profile(user_id: str) -> Dict[str, Any]:
    if user_id not in profiles:
        profiles[user_id] = {
            "user_id": user_id,
            "allergens": ["birch", "grass"],
            "threshold": 65,
            "peak_hours": ["07:00", "08:00", "18:00"],
            "symptom_log": []
        }
    return profiles[user_id]


def update_threshold(user_id: str, new_threshold: int):
    profile = get_profile(user_id)
    profile["threshold"] = new_threshold


def log_symptom(user_id: str, symptom: Dict[str, Any]):
    profile = get_profile(user_id)
    profile["symptom_log"].append(symptom)
