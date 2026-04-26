package com.example.alergoguard.network.dto;

import com.google.gson.annotations.SerializedName;

public class HardwareSignals {

    @SerializedName("close_windows")
    public boolean closeWindows;

    @SerializedName("activate_cabin_filter")
    public boolean activateCabinFilter;

    @SerializedName("reduce_speed")
    public boolean reduceSpeed;

    @SerializedName("alert_driver")
    public boolean alertDriver;
}