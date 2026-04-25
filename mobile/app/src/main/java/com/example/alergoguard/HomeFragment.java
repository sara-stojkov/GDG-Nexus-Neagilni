package com.example.alergoguard;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

public class HomeFragment extends Fragment {

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

    private View btnTracking;
    private TextView tvTrackingLabel;
    private TextView tvTrackingStatus;

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

        btnTracking     = view.findViewById(R.id.btn_tracking);
        tvTrackingLabel = view.findViewById(R.id.tv_tracking_label);
        tvTrackingStatus = view.findViewById(R.id.tv_tracking_status);

        buildForecastRows(view);
        syncTrackingButton();

        btnTracking.setOnClickListener(v -> toggleTracking());

        AlertNotificationHelper.sendTestAlert(requireContext());
    }

    @Override
    public void onResume() {
        super.onResume();
        // Re-sync button state if user came back from settings/notification
        syncTrackingButton();
    }

    // ── Tracking toggle ───────────────────────────────────────────────────────

    private void toggleTracking() {
        Intent intent = new Intent(requireContext(), TrackingService.class);
        if (TrackingService.isRunning) {
            requireContext().stopService(intent);
            TrackingService.isRunning = false; // optimistic update
        } else {
            ContextCompat.startForegroundService(requireContext(), intent);
            TrackingService.isRunning = true;  // optimistic update
        }
        syncTrackingButton();
    }

    private void syncTrackingButton() {
        if (TrackingService.isRunning) {
            btnTracking.setBackgroundResource(R.drawable.bg_tracking_active);
            tvTrackingLabel.setText("Stop tracking");
            tvTrackingStatus.setText("● Session active — tracking symptoms");
            tvTrackingStatus.setTextColor(requireContext().getColor(R.color.risk_low)); // green
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
}