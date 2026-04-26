package com.example.alergoguard;

import android.animation.ObjectAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.example.alergoguard.network.ApiClient;
import com.example.alergoguard.network.dto.PollenRiskResponse;
import com.example.alergoguard.services.RiskAlertManager;
import com.example.alergoguard.services.TrackingService;

import java.util.Locale;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class HomeFragment extends Fragment {

    private static final String TAG = "HomeFragment";

    private static final String USER_ID = "marko_petrovic";
    private static final double MOCK_LAT = 44.8176;
    private static final double MOCK_LNG = 20.4569;

    // ── Mock 7-day forecast ───────────────────────────────────────────────────
    private static final Object[][] FORECAST = {
            { "Today", "🌿", "High",   1.00f },
            { "Sun",   "🌿", "High",   0.90f },
            { "Mon",   "🌳", "High",   0.85f },
            { "Tue",   "🌳", "Medium", 0.55f },
            { "Wed",   "💨", "Medium", 0.50f },
            { "Thu",   "🍄", "Low",    0.25f },
            { "Fri",   "🍄", "Low",    0.20f },
    };

    // ── Views ─────────────────────────────────────────────────────────────────
    private View btnTracking;
    private TextView tvTrackingLabel;
    private TextView tvTrackingStatus;
    private TextView tvAiOverview;

    private ObjectAnimator criticalPulseAnimator;

    // ── YAMNet broadcast receiver ─────────────────────────────────────────────
    private final BroadcastReceiver yamnetReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean alarm = intent.getBooleanExtra("alarm", false);
            String alarmLevel = intent.getStringExtra("alarm_level");
            String advice = intent.getStringExtra("advice");

            if (alarm && advice != null) {
                tvAiOverview.setText(advice);
            }

            if (alarmLevel != null) {
                handleRiskEffects(alarmLevel, advice != null ? advice : "Symptom activity detected.");
                switch (alarmLevel) {
                    case "critical":
                        tvTrackingStatus.setText("⚠️ Symptom attack detected!");
                        tvTrackingStatus.setTextColor(requireContext().getColor(R.color.risk_high));
                        break;
                    case "warning":
                        tvTrackingStatus.setText("⚠️ Symptoms detected — check advice");
                        tvTrackingStatus.setTextColor(requireContext().getColor(R.color.risk_medium));
                        break;
                }
            }
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        btnTracking      = view.findViewById(R.id.btn_tracking);
        tvTrackingLabel  = view.findViewById(R.id.tv_tracking_label);
        tvTrackingStatus = view.findViewById(R.id.tv_tracking_status);
        tvAiOverview     = view.findViewById(R.id.tv_ai_overview);

        buildForecastRows(view);
        syncTrackingButton();
        loadPollenRisk();

        btnTracking.setOnClickListener(v -> toggleTracking());
    }

    @Override
    public void onResume() {
        super.onResume();
        syncTrackingButton();
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
                yamnetReceiver,
                new IntentFilter("com.example.alergoguard.YAMNET_RESPONSE")
        );
    }

    @Override
    public void onPause() {
        super.onPause();
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(yamnetReceiver);
        stopCriticalPulse();
    }

    @Override
    public void onDestroyView() {
        stopCriticalPulse();
        super.onDestroyView();
    }

    // ── API call ──────────────────────────────────────────────────────────────

    private void loadPollenRisk() {
        ApiClient.getService().getPollenRisk(MOCK_LAT, MOCK_LNG, USER_ID)
                .enqueue(new Callback<PollenRiskResponse>() {
                    @Override
                    public void onResponse(Call<PollenRiskResponse> call, Response<PollenRiskResponse> response) {
                        if (!response.isSuccessful() || response.body() == null) {
                            Log.w(TAG, "Pollen risk call failed: " + response.code());
                            return;
                        }
                        PollenRiskResponse body = response.body();
                        if (tvAiOverview != null) {
                            tvAiOverview.setText(body.advice);
                        }
                        handleRiskEffects(body.riskLevel, body.advice);
                        Log.i(TAG, "Pollen risk: " + body.riskLevel + " / " + body.dominantAllergen);
                    }

                    @Override
                    public void onFailure(Call<PollenRiskResponse> call, Throwable t) {
                        Log.e(TAG, "Pollen risk request failed: " + t.getMessage());
                    }
                });
    }

    // ── Tracking toggle ───────────────────────────────────────────────────────

    private void toggleTracking() {
        Intent intent = new Intent(requireContext(), TrackingService.class);
        if (TrackingService.isRunning) {
            requireContext().stopService(intent);
            TrackingService.isRunning = false;
        } else {
            ContextCompat.startForegroundService(requireContext(), intent);
            TrackingService.isRunning = true;
        }
        syncTrackingButton();
    }

    private void syncTrackingButton() {
        if (TrackingService.isRunning) {
            btnTracking.setBackgroundResource(R.drawable.bg_tracking_active);
            tvTrackingLabel.setText("Stop tracking");
            tvTrackingStatus.setText("● Session active — tracking symptoms");
            tvTrackingStatus.setTextColor(requireContext().getColor(R.color.risk_low));
        } else {
            btnTracking.setBackgroundResource(R.drawable.bg_tracking_idle);
            tvTrackingLabel.setText("Start tracking");
            tvTrackingStatus.setText("Tap to begin a symptom session");
            tvTrackingStatus.setTextColor(requireContext().getColor(R.color.text_secondary));
        }
    }

    // ── Forecast rows ─────────────────────────────────────────────────────────

    private void buildForecastRows(View root) {
        LinearLayout container = root.findViewById(R.id.container_forecast);
        LayoutInflater inflater = LayoutInflater.from(requireContext());

        for (int i = 0; i < FORECAST.length; i++) {
            String day       = (String) FORECAST[i][0];
            String emoji     = (String) FORECAST[i][1];
            String riskLabel = (String) FORECAST[i][2];
            float  fill      = (float)  FORECAST[i][3];

            View row = inflater.inflate(R.layout.item_forecast_day, container, false);

            TextView tvDay   = row.findViewById(R.id.tv_day_name);
            TextView tvEmoji = row.findViewById(R.id.tv_day_emoji);
            TextView tvRisk  = row.findViewById(R.id.tv_day_risk);
            View     barFill = row.findViewById(R.id.v_day_bar_fill);

            tvDay.setText(day);
            tvEmoji.setText(emoji);
            tvRisk.setText(riskLabel);

            int color = riskColor(riskLabel);
            tvRisk.setTextColor(color);
            barFill.setBackgroundColor(color);

            final float finalFill = fill;
            barFill.post(() -> {
                View barParent = (View) barFill.getParent();
                int targetWidth = (int) (barParent.getWidth() * finalFill);
                ViewGroup.LayoutParams lp = barFill.getLayoutParams();
                lp.width = targetWidth;
                barFill.setLayoutParams(lp);
            });

            container.addView(row);

            if (i < FORECAST.length - 1) {
                View divider = new View(requireContext());
                LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 1);
                dp.setMargins(0, 4, 0, 4);
                divider.setLayoutParams(dp);
                divider.setBackgroundColor(requireContext().getColor(R.color.divider_color));
                container.addView(divider);
            }
        }
    }

    private int riskColor(String risk) {
        switch (risk.toLowerCase()) {
            case "high":   return requireContext().getColor(R.color.risk_high);
            case "medium": return requireContext().getColor(R.color.risk_medium);
            default:       return requireContext().getColor(R.color.risk_low);
        }
    }

    private void handleRiskEffects(String rawRiskLevel, String advice) {
        RiskAlertManager alerts = getAlerts();
        if (alerts != null) {
            alerts.handleRisk(rawRiskLevel, advice);
        }

        String riskLevel = normalizeRiskLevel(rawRiskLevel);
        switch (riskLevel) {
            case "critical":
                startCriticalPulse();
                break;
            case "warning":
                stopCriticalPulse();
                break;
            default:
                stopCriticalPulse();
                break;
        }
    }

    private String normalizeRiskLevel(String rawLevel) {
        if (rawLevel == null) return "normal";
        String level = rawLevel.toLowerCase(Locale.US);
        if ("critical".equals(level) || "high".equals(level)) return "critical";
        if ("warning".equals(level) || "medium".equals(level)) return "warning";
        return "normal";
    }

    private RiskAlertManager getAlerts() {
        if (getActivity() instanceof MainActivity) {
            return ((MainActivity) getActivity()).getRiskAlertManager();
        }
        return null;
    }

    private void startCriticalPulse() {
        if (tvTrackingStatus == null) return;
        if (criticalPulseAnimator == null) {
            criticalPulseAnimator = ObjectAnimator.ofFloat(tvTrackingStatus, View.ALPHA, 1f, 0.35f, 1f);
            criticalPulseAnimator.setDuration(700);
            criticalPulseAnimator.setRepeatCount(ObjectAnimator.INFINITE);
        }
        tvTrackingStatus.setTextColor(requireContext().getColor(R.color.risk_high));
        if (!criticalPulseAnimator.isStarted()) {
            criticalPulseAnimator.start();
        }
    }

    private void stopCriticalPulse() {
        if (criticalPulseAnimator != null) {
            criticalPulseAnimator.cancel();
        }
        if (tvTrackingStatus != null) {
            tvTrackingStatus.setAlpha(1f);
        }
    }
}