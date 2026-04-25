import firebase_admin
from firebase_admin import credentials, firestore
from datetime import datetime, timezone, timedelta
import random
import os
from dotenv import load_dotenv

load_dotenv()

cred = credentials.Certificate(os.getenv("FIREBASE_CREDENTIALS"))
firebase_admin.initialize_app(cred)
db = firestore.client()

# ─── DRIVERS ──────────────────────────────────────────────────────

USERS = [
    {
        "user_id": "marko_petrovic",
        "allergens": ["birch", "grass"],
        "threshold": 55,
        "peak_hours": ["07:00", "08:00", "09:00"],
        "medications": ["Zyrtec"],
        "symptom_sensitivity": {
            "birch": 1.4,
            "grass": 1.1,
            "weed": 0.8,
            "oak": 0.9,
            "pine": 0.7
        },
        "profile": {
            "route_lats": [44.80, 44.85, 44.95, 45.10, 45.25],
            "route_lngs": [20.46, 20.50, 20.55, 20.60, 20.65],
            "typical_sneezes_high": (3, 6),
            "typical_sneezes_medium": (1, 3),
            "med_compliance": 0.6,
            "drives_per_day": 2
        }
    },
    {
        "user_id": "ana_jovanovic",
        "allergens": ["grass", "weed"],
        "threshold": 70,
        "peak_hours": ["06:00", "07:00", "18:00", "19:00"],
        "medications": ["Claritin", "Flonase"],
        "symptom_sensitivity": {
            "birch": 0.6,
            "grass": 1.6,
            "weed": 1.5,
            "oak": 0.5,
            "pine": 0.4
        },
        "profile": {
            "route_lats": [44.78, 44.80, 44.82, 44.84],
            "route_lngs": [20.40, 20.42, 20.44, 20.46],
            "typical_sneezes_high": (2, 4),
            "typical_sneezes_medium": (0, 2),
            "med_compliance": 0.85,
            "drives_per_day": 4
        }
    },
    {
        "user_id": "stefan_nikolic",
        "allergens": ["oak", "pine", "birch"],
        "threshold": 40,
        "peak_hours": ["08:00", "09:00", "10:00"],
        "medications": ["Telfast"],
        "symptom_sensitivity": {
            "birch": 1.8,
            "grass": 1.0,
            "weed": 0.9,
            "oak": 2.0,
            "pine": 1.7
        },
        "profile": {
            "route_lats": [44.75, 44.78, 44.82, 44.87, 44.92],
            "route_lngs": [20.35, 20.38, 20.41, 20.44, 20.47],
            "typical_sneezes_high": (5, 9),
            "typical_sneezes_medium": (2, 4),
            "med_compliance": 0.4,
            "drives_per_day": 1
        }
    },
    {
        "user_id": "maja_stojanovic",
        "allergens": ["grass"],
        "threshold": 80,
        "peak_hours": ["17:00", "18:00", "19:00"],
        "medications": ["Aerius"],
        "symptom_sensitivity": {
            "birch": 0.5,
            "grass": 1.2,
            "weed": 0.7,
            "oak": 0.4,
            "pine": 0.3
        },
        "profile": {
            "route_lats": [44.82, 44.84, 44.86],
            "route_lngs": [20.42, 20.44, 20.46],
            "typical_sneezes_high": (1, 3),
            "typical_sneezes_medium": (0, 1),
            "med_compliance": 0.9,
            "drives_per_day": 2
        }
    },
    {
        "user_id": "nikola_dimitrijevic",
        "allergens": ["birch", "oak", "grass", "weed"],
        "threshold": 30,
        "peak_hours": ["06:00", "07:00", "08:00", "17:00", "18:00"],
        "medications": ["Zyrtec", "Flonase", "Montelukast"],
        "symptom_sensitivity": {
            "birch": 2.0,
            "grass": 1.9,
            "weed": 1.8,
            "oak": 2.0,
            "pine": 1.5
        },
        "profile": {
            "route_lats": [44.77, 44.80, 44.83, 44.86, 44.89, 44.92],
            "route_lngs": [20.38, 20.41, 20.44, 20.47, 20.50, 20.53],
            "typical_sneezes_high": (7, 12),
            "typical_sneezes_medium": (3, 6),
            "med_compliance": 0.75,
            "drives_per_day": 3
        }
    }
]

ALLERGENS = ["birch", "grass", "weed", "oak", "pine"]


# ─── HELPERS ─────────────────────────────────────────────────────

def random_pollen_score(hour: int, risk_profile: str = "medium") -> tuple:
    base_scores = {
        "low": (10, 35),
        "medium": (35, 65),
        "high": (65, 95)
    }

    if 6 <= hour <= 10:
        multiplier = 1.3
    elif 17 <= hour <= 20:
        multiplier = 1.1
    else:
        multiplier = 0.8

    low, high = base_scores[risk_profile]
    score = int(random.randint(low, high) * multiplier)
    score = min(100, max(0, score))

    if score <= 35:
        risk = "low"
    elif score <= 65:
        risk = "medium"
    else:
        risk = "high"

    return score, risk


def make_dt(days_ago: int, hour: int, minute: int = 0) -> datetime:
    dt = datetime.now(timezone.utc) - timedelta(days=days_ago)
    return dt.replace(hour=hour, minute=minute, second=0, microsecond=0)


def clear_user(uid: str):
    subcollections = ["medications", "symptom_events", "mucosa_scores", "location_history"]
    for sub in subcollections:
        docs = db.collection("users").document(uid).collection(sub).stream()
        for doc in docs:
            doc.reference.delete()
    db.collection("users").document(uid).delete()
    print(f"  ✗ {uid} deleted")


def seed_user(user: dict):
    uid = user["user_id"]
    p = user["profile"]
    print(f"\n→ Seeding {uid}...")

    clear_user(uid)

    db.collection("users").document(uid).set({
        "user_id": uid,
        "allergens": user["allergens"],
        "threshold": user["threshold"],
        "peak_hours": user["peak_hours"],
        "symptom_sensitivity": user["symptom_sensitivity"],
        "created_at": make_dt(30, 10)  # datetime, ne string
    })

    # ── 14 days history ────────────────────────────────────────────
    for day in range(14, 0, -1):

        season_intensity = "high" if day <= 7 else "medium"

        # ── Medicine ─────────────────────────────────────────────────────
        if random.random() < p["med_compliance"]:
            med_name = random.choice(user["medications"])
            db.collection("users").document(uid).collection("medications").add({
                "name": med_name,
                "dose_mg": random.choice([5, 10, 20]),
                "logged_at": make_dt(day, random.randint(6, 9))  # datetime
            })

            if random.random() < 0.3:
                db.collection("users").document(uid).collection("medications").add({
                    "name": med_name,
                    "dose_mg": random.choice([5, 10]),
                    "logged_at": make_dt(day, random.randint(20, 22))  # datetime
                })

        # ── Rides ──────────────────────────────────────────────────
        drive_hours = random.sample(
            [7, 8, 9, 17, 18],
            min(p["drives_per_day"], 5)
        )

        for hour in drive_hours:
            pollen_score, risk_level = random_pollen_score(hour, season_intensity)
            dominant = random.choice(user["allergens"])

            idx = random.randint(0, len(p["route_lats"]) - 1)
            lat = p["route_lats"][idx] + random.uniform(-0.01, 0.01)
            lng = p["route_lngs"][idx] + random.uniform(-0.01, 0.01)

            triggered_alarm = risk_level == "high"

            db.collection("users").document(uid).collection("location_history").add({
                "lat": lat,
                "lng": lng,
                "heading": random.uniform(0, 360),
                "speed": random.uniform(30, 120),
                "pollen_score": pollen_score,
                "dominant_allergen": dominant,
                "risk_level": risk_level,
                "triggered_alarm": triggered_alarm,
                "timestamp": make_dt(day, hour, random.randint(0, 59))  # datetime
            })

            # ── Symptoms ──────────────────────────────
            if risk_level == "high":
                sneeze_count = random.randint(*p["typical_sneezes_high"])
            elif risk_level == "medium":
                sneeze_count = random.randint(*p["typical_sneezes_medium"])
            else:
                sneeze_count = 0

            if sneeze_count > 0:
                med_taken = random.random() < p["med_compliance"]

                db.collection("users").document(uid).collection("symptom_events").add({
                    "type": "sneeze",
                    "count": sneeze_count,
                    "lat": lat,
                    "lng": lng,
                    "pollen_score": pollen_score,
                    "dominant_allergen": dominant,
                    "medication_taken_last_2h": med_taken,
                    "mucosa_score": random.randint(30, 85),
                    "yamnet_confidence": round(random.uniform(0.55, 0.98), 2),
                    "source": "yamnet",
                    "timestamp": make_dt(day, hour, random.randint(5, 55))  # datetime
                })

            if random.random() < 0.3 and risk_level != "low":
                db.collection("users").document(uid).collection("symptom_events").add({
                    "type": "cough",
                    "count": random.randint(1, 4),
                    "lat": lat,
                    "lng": lng,
                    "pollen_score": pollen_score,
                    "dominant_allergen": dominant,
                    "medication_taken_last_2h": random.random() < p["med_compliance"],
                    "mucosa_score": random.randint(40, 90),
                    "yamnet_confidence": round(random.uniform(0.5, 0.92), 2),
                    "source": "yamnet",
                    "timestamp": make_dt(day, hour, random.randint(5, 55))  # datetime
                })

        # ── Mucosa scores ─────────────────────────────────────────
        for _ in range(random.randint(1, 2)):
            score_base = 50 if season_intensity == "high" else 35
            db.collection("users").document(uid).collection("mucosa_scores").add({
                "score": random.randint(score_base - 15, score_base + 25),
                "source": "mock",
                "metadata": {"model_version": "mock_v1", "image_provided": False},
                "timestamp": make_dt(day, random.randint(8, 22))  # datetime
            })

    print(f"✓ {uid} done")


# ─── MAIN ─────────────────────────────────────────────────────────

def main():
    print("Seeding AlergoGuard Firebase...")
    for user in USERS:
        seed_user(user)
    print("\n✓ Seed complete. All 5 drivers loaded with 14 days of history.")


if __name__ == "__main__":
    main()
