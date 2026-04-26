package com.example.alergoguard.network.dto;

import com.google.gson.annotations.SerializedName;

public class YamNetEventResponse {

    @SerializedName("alarm")
    public boolean alarm;

    @SerializedName("alarm_level")
    public String alarmLevel;

    @SerializedName("advice")
    public String advice;

    @SerializedName("risk_explanation")
    public String riskExplanation;

    @SerializedName("hardware_signals")
    public HardwareSignals hardwareSignals;

    @SerializedName("threshold_updated")
    public boolean thresholdUpdated;

    @SerializedName("new_threshold")
    public int newThreshold;
}