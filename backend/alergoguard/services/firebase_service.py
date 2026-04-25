import firebase_admin
from firebase_admin import credentials, firestore
from datetime import datetime, timezone
import os
from dotenv import load_dotenv

load_dotenv()

# Initialize Firebase
cred = credentials.Certificate(os.getenv("FIREBASE_CREDENTIALS"))
firebase_admin.initialize_app(cred)
db = firestore.client()


# ─── USER PROFILE ────────────────────────────────────────────────

def get_or_create_profile(user_id: str) -> dict:
    ref = db.collection("users").document(user_id)
    doc = ref.get()

    if doc.exists:
        return doc.to_dict()

    default_profile = {
        "user_id": user_id,
        "allergens": ["birch", "grass"],
        "threshold": 65,
        "peak_hours": ["07:00", "08:00", "18:00"],
        "medications": [],
        "symptom_sensitivity": {
            "birch": 1.0,
            "grass": 1.0,
            "weed": 1.0,
            "oak": 1.0,
            "pine": 1.0
        },
        "created_at": datetime.now(timezone.utc).isoformat()
    }

    ref.set(default_profile)
    return default_profile


def update_profile(user_id: str, data: dict):
    db.collection("users").document(user_id).update(data)


def update_threshold(user_id: str, new_threshold: int):
    update_profile(user_id, {"threshold": new_threshold})


def update_sensitivity(user_id: str, allergen: str, delta: float):
    profile = get_or_create_profile(user_id)
    sensitivity = profile.get("symptom_sensitivity", {})
    current = sensitivity.get(allergen, 1.0)
    # Save in range 0.5 - 2.0
    new_value = round(max(0.5, min(2.0, current + delta)), 2)
    db.collection("users").document(user_id).update({
        f"symptom_sensitivity.{allergen}": new_value
    })
    return new_value


# ─── MEDICATIONS ─────────────────────────────────────────────────

def log_medication(user_id: str, medication: dict):
    """
    medication: {
        "name": "Zyrtec",
        "dose_mg": 10,
        "taken_at": "2024-04-01T08:00:00Z"
    }
    """
    db.collection("users").document(user_id).collection("medications").add({
        **medication,
        "logged_at": datetime.now(timezone.utc).isoformat()
    })


def get_recent_medications(user_id: str, hours: int = 12) -> list:
    from datetime import timedelta
    cutoff = datetime.now(timezone.utc) - timedelta(hours=hours)

    docs = (
        db.collection("users")
        .document(user_id)
        .collection("medications")
        .where("logged_at", ">=", cutoff.isoformat())
        .stream()
    )
    return [doc.to_dict() for doc in docs]


# ─── SYMPTOM EVENTS ──────────────────────────────────────────────

def log_symptom_event(user_id: str, event: dict):
    """
    event: {
        "type": "sneeze" | "cough",
        "count": 3,
        "lat": 44.8,
        "lng": 20.4,
        "pollen_score": 75,
        "dominant_allergen": "birch",
        "medication_taken_last_2h": true,
        "mucosa_score": 60
    }
    """
    db.collection("users").document(user_id).collection("symptom_events").add({
        **event,
        "timestamp": datetime.now(timezone.utc).isoformat()
    })


def get_recent_symptom_events(user_id: str, hours: int = 48) -> list:
    from datetime import timedelta
    cutoff = datetime.now(timezone.utc) - timedelta(hours=hours)

    docs = (
        db.collection("users")
        .document(user_id)
        .collection("symptom_events")
        .where("timestamp", ">=", cutoff.isoformat())
        .order_by("timestamp", direction=firestore.Query.DESCENDING)
        .limit(50)
        .stream()
    )
    return [doc.to_dict() for doc in docs]


def get_symptom_pollen_correlation(user_id: str) -> dict:
    """
    It calculates the correlation between the pollen score and the frequency of symptoms.
    Returns the average pollen score when symptoms were present.
    """
    events = get_recent_symptom_events(user_id, hours=720)  # 30 days

    if not events:
        return {"correlation": "insufficient_data", "avg_trigger_score": None}

    scores_with_symptoms = [e["pollen_score"] for e in events if e.get("pollen_score")]

    if not scores_with_symptoms:
        return {"correlation": "insufficient_data", "avg_trigger_score": None}

    avg_trigger_score = sum(scores_with_symptoms) / len(scores_with_symptoms)
    return {
        "correlation": "available",
        "avg_trigger_score": round(avg_trigger_score, 1),
        "total_events": len(events),
        "events_last_48h": len([
            e for e in events
            if e.get("pollen_score")
        ])
    }


# ─── MUCOSA SCORES ───────────────────────────────────────────────

def log_mucosa_score(user_id: str, score: int, source: str = "mock", metadata: dict = None):
    """
    source: "mock" | "manual" | "image_model"
    metadata: additional data when the real model arrives (eg image_url, model_version)
    Hook for future integration with the vision model.
    """
    db.collection("users").document(user_id).collection("mucosa_scores").add({
        "score": score,
        "source": source,
        "metadata": metadata or {},
        "timestamp": datetime.now(timezone.utc).isoformat()
    })


def get_recent_mucosa_scores(user_id: str, hours: int = 24) -> list:
    from datetime import timedelta
    cutoff = datetime.now(timezone.utc) - timedelta(hours=hours)

    docs = (
        db.collection("users")
        .document(user_id)
        .collection("mucosa_scores")
        .where("timestamp", ">=", cutoff.isoformat())
        .order_by("timestamp", direction=firestore.Query.DESCENDING)
        .limit(10)
        .stream()
    )
    return [doc.to_dict() for doc in docs]


def get_mucosa_trend(user_id: str) -> dict:
    """
    It reverses the trend of the mucosa — worsening or improving.
    """
    scores = get_recent_mucosa_scores(user_id, hours=24)

    if len(scores) < 2:
        return {"trend": "insufficient_data", "latest_score": scores[0]["score"] if scores else None}

    latest = scores[0]["score"]
    previous = scores[-1]["score"]
    delta = latest - previous

    if delta > 10:
        trend = "worsening"
    elif delta < -10:
        trend = "improving"
    else:
        trend = "stable"

    return {
        "trend": trend,
        "latest_score": latest,
        "delta": delta,
        "data_points": len(scores)
    }


# ─── LOCATION HISTORY ────────────────────────────────────────────

def log_location_event(user_id: str, event: dict):
    """
    event: {
        "lat": 44.8,
        "lng": 20.4,
        "heading": 45.0,
        "pollen_score": 75,
        "dominant_allergen": "birch",
        "risk_level": "high",
        "triggered_alarm": true
    }
    """
    db.collection("users").document(user_id).collection("location_history").add({
        **event,
        "timestamp": datetime.now(timezone.utc).isoformat()
    })


def get_historical_hotspots(user_id: str) -> list:
    """
    Returns the locations where the user has historically had alarms.
    """
    docs = (
        db.collection("users")
        .document(user_id)
        .collection("location_history")
        .where("triggered_alarm", "==", True)
        .limit(20)
        .stream()
    )
    return [doc.to_dict() for doc in docs]
