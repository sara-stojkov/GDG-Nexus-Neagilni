import firebase_admin
from firebase_admin import credentials, firestore
from google.cloud.firestore_v1.base_query import FieldFilter
from datetime import datetime, timezone, timedelta
import os
import logging
from dotenv import load_dotenv

load_dotenv()

logger = logging.getLogger(__name__)

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
        "created_at": datetime.now(timezone.utc)
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
    new_value = round(max(0.5, min(2.0, current + delta)), 2)
    db.collection("users").document(user_id).update({
        f"symptom_sensitivity.{allergen}": new_value
    })
    return new_value


# ─── MEDICATIONS ─────────────────────────────────────────────────

def log_medication(user_id: str, medication: dict):
    db.collection("users").document(user_id).collection("medications").add({
        **medication,
        "logged_at": datetime.now(timezone.utc)
    })


def get_recent_medications(user_id: str, hours: int = 12) -> list:
    cutoff = datetime.now(timezone.utc) - timedelta(hours=hours)

    docs = (
        db.collection("users")
        .document(user_id)
        .collection("medications")
        .where(filter=FieldFilter("logged_at", ">=", cutoff))
        .order_by("logged_at", direction=firestore.Query.DESCENDING)
        .stream()
    )
    return [doc.to_dict() for doc in docs]


# ─── SYMPTOM EVENTS ──────────────────────────────────────────────

def log_symptom_event(user_id: str, event: dict):
    db.collection("users").document(user_id).collection("symptom_events").add({
        **event,
        "timestamp": datetime.now(timezone.utc)
    })


def get_recent_symptom_events(user_id: str, hours: int = 48) -> list:
    cutoff = datetime.now(timezone.utc) - timedelta(hours=hours)

    docs = (
        db.collection("users")
        .document(user_id)
        .collection("symptom_events")
        .where(filter=FieldFilter("timestamp", ">=", cutoff))
        .order_by("timestamp", direction=firestore.Query.DESCENDING)
        .limit(50)
        .stream()
    )
    return [doc.to_dict() for doc in docs]


def get_symptom_pollen_correlation(user_id: str) -> dict:
    cutoff = datetime.now(timezone.utc) - timedelta(days=30)
    PAGE_SIZE = 100

    total_score = 0.0
    total_count = 0
    event_count = 0
    last_doc = None

    while True:
        query = (
            db.collection("users")
            .document(user_id)
            .collection("symptom_events")
            .where(filter=FieldFilter("timestamp", ">=", cutoff))
            .order_by("timestamp")
            .limit(PAGE_SIZE)
        )

        if last_doc:
            query = query.start_after(last_doc)

        docs = list(query.stream())
        if not docs:
            break

        for doc in docs:
            data = doc.to_dict()
            event_count += 1
            score = data.get("pollen_score")
            if score is not None:
                total_score += score
                total_count += 1

        if len(docs) < PAGE_SIZE:
            break

        last_doc = docs[-1]

    if total_count == 0:
        return {"correlation": "insufficient_data", "avg_trigger_score": None}

    return {
        "correlation": "available",
        "avg_trigger_score": round(total_score / total_count, 1),
        "total_events": event_count,
        "events_with_score": total_count,
    }


# ─── MUCOSA SCORES ───────────────────────────────────────────────

def log_mucosa_score(user_id: str, score: int, source: str = "mock", metadata: dict = None):
    db.collection("users").document(user_id).collection("mucosa_scores").add({
        "score": score,
        "source": source,
        "metadata": metadata or {},
        "timestamp": datetime.now(timezone.utc)
    })


def get_recent_mucosa_scores(user_id: str, hours: int = 24) -> list:
    cutoff = datetime.now(timezone.utc) - timedelta(hours=hours)

    docs = (
        db.collection("users")
        .document(user_id)
        .collection("mucosa_scores")
        .where(filter=FieldFilter("timestamp", ">=", cutoff))
        .order_by("timestamp", direction=firestore.Query.DESCENDING)
        .limit(10)
        .stream()
    )
    return [doc.to_dict() for doc in docs]


def get_mucosa_trend(user_id: str) -> dict:
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
    db.collection("users").document(user_id).collection("location_history").add({
        **event,
        "timestamp": datetime.now(timezone.utc)
    })


def get_historical_hotspots(user_id: str) -> list:
    docs = (
        db.collection("users")
        .document(user_id)
        .collection("location_history")
        .where(filter=FieldFilter("triggered_alarm", "==", True))
        .limit(20)
        .stream()
    )
    return [doc.to_dict() for doc in docs]
