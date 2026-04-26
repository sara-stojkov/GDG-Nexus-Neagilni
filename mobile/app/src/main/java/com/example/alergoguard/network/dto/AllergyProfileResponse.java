package com.example.alergoguard.network.dto;

import com.google.gson.annotations.SerializedName;
import java.util.List;
import java.util.Map;

public class AllergyProfileResponse {

    @SerializedName("user_id")
    public String userId;

    @SerializedName("allergens")
    public List<String> allergens;

    @SerializedName("threshold")
    public int threshold;

    @SerializedName("peak_hours")
    public List<String> peakHours;

    @SerializedName("symptom_sensitivity")
    public Map<String, Double> symptomSensitivity;
}