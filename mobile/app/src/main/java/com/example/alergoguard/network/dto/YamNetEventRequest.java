package com.example.alergoguard.network.dto;

import com.google.gson.annotations.SerializedName;

public class YamNetEventRequest {

    @SerializedName("user_id")
    public String userId;

    @SerializedName("event_type")
    public String eventType;

    @SerializedName("confidence")
    public float confidence;

    @SerializedName("lat")
    public double lat;

    @SerializedName("lng")
    public double lng;

    public YamNetEventRequest(String userId, String eventType, float confidence, double lat, double lng) {
        this.userId = userId;
        this.eventType = eventType;
        this.confidence = confidence;
        this.lat = lat;
        this.lng = lng;
    }
}