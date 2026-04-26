package com.example.alergoguard.network.dto;

import com.google.gson.annotations.SerializedName;

public class SymptomLogResponse {

    @SerializedName("allergy_id_updated")
    public boolean allergyIdUpdated;

    @SerializedName("new_threshold")
    public int newThreshold;

    @SerializedName("sensitivity_updated")
    public boolean sensitivityUpdated;
}