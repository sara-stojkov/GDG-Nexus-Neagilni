package com.example.alergoguard.network;

import com.example.alergoguard.network.dto.AllergyProfileResponse;
import com.example.alergoguard.network.dto.LocationUpdateRequest;
import com.example.alergoguard.network.dto.LocationUpdateResponse;
import com.example.alergoguard.network.dto.MedicationLogRequest;
import com.example.alergoguard.network.dto.PollenRiskResponse;
import com.example.alergoguard.network.dto.SymptomLogRequest;
import com.example.alergoguard.network.dto.SymptomLogResponse;
import com.example.alergoguard.network.dto.YamNetEventRequest;
import com.example.alergoguard.network.dto.YamNetEventResponse;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Query;

public interface ApiService {

    // ── YAMNet ────────────────────────────────────────────────────────────────

    @POST("yamnet/event")
    Call<YamNetEventResponse> sendYamNetEvent(@Body YamNetEventRequest request);

    // ── Pollen ────────────────────────────────────────────────────────────────

    @POST("pollen/location-update")
    Call<LocationUpdateResponse> locationUpdate(@Body LocationUpdateRequest request);

    @GET("pollen/risk")
    Call<PollenRiskResponse> getPollenRisk(
            @Query("lat") double lat,
            @Query("lng") double lng,
            @Query("user_id") String userId
    );

    // ── Symptoms ──────────────────────────────────────────────────────────────

    @POST("symptoms/log")
    Call<SymptomLogResponse> logSymptom(@Body SymptomLogRequest request);

    @POST("symptoms/medication")
    Call<Void> logMedication(@Body MedicationLogRequest request);

    // ── Profile ───────────────────────────────────────────────────────────────

    @GET("profile/allergy-profile")
    Call<AllergyProfileResponse> getAllergyProfile(@Query("user_id") String userId);
}