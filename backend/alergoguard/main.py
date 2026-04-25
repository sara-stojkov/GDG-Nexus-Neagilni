from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from routers import pollen, symptoms, profile

app = FastAPI(title="AlergoGuard API", version="1.0.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(pollen.router, prefix="/pollen", tags=["pollen"])
app.include_router(symptoms.router, prefix="/symptoms", tags=["symptoms"])
app.include_router(profile.router, prefix="/profile", tags=["profile"])


@app.get("/health")
def health_check():
    return {"status": "ok", "service": "AlergoGuard"}
