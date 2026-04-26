package com.example.alergoguard.network.dto;

import com.google.gson.annotations.SerializedName;

public class LocationUpdateRequest {

    @SerializedName("user_id")
    public String userId;

    @SerializedName("lat")
    public double lat;

    @SerializedName("lng")
    public double lng;

    @SerializedName("heading")
    public float heading;

    @SerializedName("speed")
    public Float speed;

    public LocationUpdateRequest(String userId, double lat, double lng, float heading, Float speed) {
        this.userId = userId;
        this.lat = lat;
        this.lng = lng;
        this.heading = heading;
        this.speed = speed;
    }
}