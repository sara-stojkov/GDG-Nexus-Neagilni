package com.example.alergoguard.network.dto;

import com.google.gson.annotations.SerializedName;

public class SymptomLogRequest {

    @SerializedName("user_id")
    public String userId;

    @SerializedName("type")
    public String type;

    @SerializedName("count")
    public int count;

    @SerializedName("lat")
    public double lat;

    @SerializedName("lng")
    public double lng;

    @SerializedName("pollen_score")
    public Integer pollenScore;

    @SerializedName("dominant_allergen")
    public String dominantAllergen;

    @SerializedName("medication_taken_last_2h")
    public Boolean medicationTakenLast2h;

    @SerializedName("mucosa_score")
    public Integer mucosaScore;

    public SymptomLogRequest(String userId, String type, int count, double lat, double lng) {
        this.userId = userId;
        this.type = type;
        this.count = count;
        this.lat = lat;
        this.lng = lng;
    }
}