package com.example.alergoguard.network.dto;

import com.google.gson.annotations.SerializedName;

public class MedicationLogRequest {

    @SerializedName("user_id")
    public String userId;

    @SerializedName("name")
    public String name;

    @SerializedName("dose_mg")
    public Integer doseMg;

    public MedicationLogRequest(String userId, String name, Integer doseMg) {
        this.userId = userId;
        this.name = name;
        this.doseMg = doseMg;
    }
}