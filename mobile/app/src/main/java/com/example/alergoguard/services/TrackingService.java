package com.example.alergoguard.services;

import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import androidx.annotation.Nullable;
import androidx.core.app.ServiceCompat;

import com.example.alergoguard.AlertNotificationHelper;

public class TrackingService extends Service {

    public static boolean isRunning = false;

    private final IBinder binder = new LocalBinder();

    public class LocalBinder extends Binder {
        public TrackingService getService() { return TrackingService.this; }
    }

    // ── ADD THIS SECTION ──────────────────────────────────────────────────
    @Override
    public void onCreate() {
        super.onCreate();
        // This ensures channels exist even if MainActivity hasn't started yet
        AlertNotificationHelper.init(this);
    }
    // ──────────────────────────────────────────────────────────────────────

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // We use ServiceCompat to handle the new foreground requirements safely
        ServiceCompat.startForeground(
                this,
                AlertNotificationHelper.NOTIF_ID_TRACKING,
                AlertNotificationHelper.buildTrackingNotification(this),
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                        ? ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                        : 0
        );

        isRunning = true;
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return binder; }
}