package com.example.alergoguard.network.dto;

import com.google.gson.annotations.SerializedName;

public class LocationUpdateResponse {

    @SerializedName("risk_score")
    public int riskScore;

    @SerializedName("risk_level")
    public String riskLevel;

    @SerializedName("dominant_allergen")
    public String dominantAllergen;

    @SerializedName("advice")
    public String advice;

    @SerializedName("risk_explanation")
    public String riskExplanation;

    @SerializedName("hardware_signals")
    public HardwareSignals hardwareSignals;

    @SerializedName("lookahead_risk")
    public String lookaheadRisk;
}