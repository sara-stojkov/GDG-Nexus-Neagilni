package com.example.alergoguard.network.dto;

import com.google.gson.annotations.SerializedName;

public class PollenRiskResponse {

    @SerializedName("risk_level")
    public String riskLevel;

    @SerializedName("dominant_allergen")
    public String dominantAllergen;

    @SerializedName("score")
    public int score;

    @SerializedName("advice")
    public String advice;
}