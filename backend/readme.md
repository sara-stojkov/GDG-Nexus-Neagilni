# Backend 

# Endpoints

GET  /health

POST /pollen/location-update     ← glavni endpoint, šalje se svaki GPS update
GET  /pollen/risk                ← jednostavan test

POST /symptoms/log               ← manuelni unos simptoma
POST /symptoms/medication        ← logovanje leka
POST /symptoms/mucosa            ← logovanje stanja mukoze

GET  /profile/allergy-profile    ← osnovni profil
GET  /profile/full-context       ← kompletan snapshot za prikaz
PUT  /profile/{user_id}/allergens
PUT  /profile/{user_id}/threshold
PUT  /profile/{user_id}/peak-hours
DELETE /profile/{user_id}/reset-sensitivity

POST /yamnet/event               ← YAMNet signal sa Androida
GET  /yamnet/recent-events       ← istorija YAMNet događaja

# Firebase structure

users/
└── {user_id}/
    ├── (dokument)               ← profil, allergens, threshold, sensitivity
    ├── medications/             ← subcollection
    ├── symptom_events/          ← subcollection
    ├── mucosa_scores/           ← subcollection
    └── location_history/        ← subcollection
