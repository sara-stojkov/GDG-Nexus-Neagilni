package com.example.alergoguard;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.NavigationUI;

import com.example.alergoguard.audio.SneezeDetectorService;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.snackbar.Snackbar;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private NavController navController;

    // ── Permission launcher ───────────────────────────────────────────────────
    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestMultiplePermissions(),
                    results -> {
                        boolean micGranted = Boolean.TRUE.equals(
                                results.get(Manifest.permission.RECORD_AUDIO));
                        if (micGranted) {
                            Log.i(TAG, "Mic permission granted — starting sneeze detector");
                            startSneezeService();
                        } else {
                            Log.w(TAG, "Mic permission denied — sneeze detection unavailable");
                            showPermissionDeniedBanner();
                        }
                    });

    // ── Sneeze broadcast receiver ─────────────────────────────────────────────
    private final BroadcastReceiver sneezeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (intent == null) return;
            if (SneezeDetectorService.ACTION_SNEEZE.equals(intent.getAction())) {
                float score  = intent.getFloatExtra(SneezeDetectorService.EXTRA_SCORE, 0f);
                String label = intent.getStringExtra(SneezeDetectorService.EXTRA_LABEL);
                if (label == null) label = "Sneeze"; // safe fallback
                onSneezeDetected(score, label);
            }
            // ACTION_SCORE (raw per-frame float) is intentionally ignored here —
            // HomeFragment already handles it and we would flood the UI otherwise.
        }
    };

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // --- your original navigation setup, untouched ---
        NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager()
                .findFragmentById(R.id.nav_host_fragment);
        navController = navHostFragment.getNavController();

        BottomNavigationView bottomNav = findViewById(R.id.bottom_navigation);
        NavigationUI.setupWithNavController(bottomNav, navController);
        // --------------------------------------------------

        requestRequiredPermissions(); // auto-start sneeze detector
    }

    @Override
    public boolean onSupportNavigateUp() {
        return navController.navigateUp() || super.onSupportNavigateUp();
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(SneezeDetectorService.ACTION_SNEEZE);
        LocalBroadcastManager.getInstance(this).registerReceiver(sneezeReceiver, filter);
    }

    @Override
    protected void onPause() {
        super.onPause();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(sneezeReceiver);
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    private void requestRequiredPermissions() {
        boolean micOk = ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;

        boolean notifOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                        this, Manifest.permission.POST_NOTIFICATIONS)
                        == PackageManager.PERMISSION_GRANTED;

        if (micOk && notifOk) {
            Log.i(TAG, "All permissions already granted — starting sneeze detector");
            startSneezeService();
        } else {
            permissionLauncher.launch(buildPermissionRequest(micOk, notifOk));
        }
    }

    @NonNull
    private String[] buildPermissionRequest(boolean micOk, boolean notifOk) {
        if (!micOk && !notifOk && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS};
        } else if (!micOk) {
            return new String[]{Manifest.permission.RECORD_AUDIO};
        } else {
            return new String[]{Manifest.permission.POST_NOTIFICATIONS};
        }
    }

    // ── Service ───────────────────────────────────────────────────────────────

    private void startSneezeService() {
        Intent intent = new Intent(this, SneezeDetectorService.class);
        ContextCompat.startForegroundService(this, intent);
        Log.i(TAG, "✅ SneezeDetectorService started");
    }

    // ── Sneeze event UI ───────────────────────────────────────────────────────

    private void onSneezeDetected(float score, String label) {
        int pct = Math.round(score * 100);
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        String emoji = "Sneeze".equals(label) ? "🤧" : "😮";

        String msg;
        int bgColor;
        if (pct >= 70) {
            msg     = emoji + " " + label + " detected! " + pct + "% — " + time;
            bgColor = Color.parseColor("#C62828"); // red
            Log.e(TAG, "🔴 HIGH " + label + " — " + pct + "% at " + time);
        } else if (pct >= 40) {
            msg     = emoji + " Possible " + label.toLowerCase() + " — " + pct + "% — " + time;
            bgColor = Color.parseColor("#E65100"); // orange
            Log.w(TAG, "🟠 MEDIUM " + label + " — " + pct + "% at " + time);
        } else {
            msg     = emoji + " Low-confidence " + label.toLowerCase() + " — " + pct + "% — " + time;
            bgColor = Color.parseColor("#1565C0"); // blue
            Log.i(TAG, "🔵 LOW " + label + " — " + pct + "% at " + time);
        }

        View rootView = findViewById(android.R.id.content);
        View anchor   = findViewById(R.id.bottom_navigation);

        Snackbar snackbar = Snackbar.make(rootView, msg, Snackbar.LENGTH_LONG);
        if (anchor != null) snackbar.setAnchorView(anchor);

        View snackView = snackbar.getView();
        snackView.setBackgroundColor(bgColor);

        TextView tv = snackView.findViewById(com.google.android.material.R.id.snackbar_text);
        if (tv != null) {
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(13f);
            tv.setMaxLines(2);
        }

        snackbar.show();
    }

    private void showPermissionDeniedBanner() {
        View rootView = findViewById(android.R.id.content);
        Snackbar snackbar = Snackbar.make(
                rootView,
                "⚠ Microphone permission denied — sneeze detection is off",
                Snackbar.LENGTH_INDEFINITE);
        snackbar.setAction("Retry", v -> requestRequiredPermissions());
        snackbar.getView().setBackgroundColor(Color.parseColor("#37474F"));
        snackbar.show();
    }
}