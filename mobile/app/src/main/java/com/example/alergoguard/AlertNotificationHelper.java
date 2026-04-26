package com.example.alergoguard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import androidx.core.app.NotificationCompat;

/**
 * Central helper for all notifications in AlergoGuard.
 *
 * Two channels:
 *  - CHANNEL_TRACKING : silent persistent "session active" notification
 *  - CHANNEL_ALERT    : urgent beeping alert for high pollen / symptom spikes
 *
 * Usage:
 *   AlertNotificationHelper.init(context);          // call once in MainActivity.onCreate()
 *   AlertNotificationHelper.sendAlert(context, "High grass pollen!", "Consider staying indoors.");
 *   AlertNotificationHelper.cancelAlert(context);   // dismiss the alert
 */
public class AlertNotificationHelper {

    public static final String CHANNEL_TRACKING = "alergoguard_tracking";
    public static final String CHANNEL_ALERT    = "alergoguard_alert";

    public static final int NOTIF_ID_TRACKING = 1001;
    public static final int NOTIF_ID_ALERT    = 1002;

    // ── Init — call once from MainActivity.onCreate() ─────────────────────────

    public static void init(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);

        // Silent channel for the ongoing tracking session
        NotificationChannel tracking = new NotificationChannel(
                CHANNEL_TRACKING,
                "Tracking session",
                NotificationManager.IMPORTANCE_LOW
        );
        tracking.setDescription("Active while AlergoGuard is monitoring your symptoms.");
        tracking.setSound(null, null);
        nm.createNotificationChannel(tracking);

        // Urgent channel with sound + vibration for pollen/symptom alerts
        Uri alertSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);

        AudioAttributes audioAttr = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();

        NotificationChannel alert = new NotificationChannel(
                CHANNEL_ALERT,
                "Allergy alerts",
                NotificationManager.IMPORTANCE_HIGH
        );
        alert.setDescription("Urgent pollen spike or symptom threshold alerts.");
        alert.setSound(alertSound, audioAttr);
        alert.enableVibration(true);
        alert.setVibrationPattern(new long[]{0, 400, 200, 400, 200, 400}); // beep-beep-beep pattern
        alert.enableLights(true);
        nm.createNotificationChannel(alert);
    }

    // ── Tracking notification (used by TrackingService) ───────────────────────

    public static Notification buildTrackingNotification(Context ctx) {
        Intent openApp = new Intent(ctx, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                ctx, 0, openApp,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        return new NotificationCompat.Builder(ctx, CHANNEL_TRACKING)
                .setContentTitle("AlergoGuard is tracking")
                .setContentText("Monitoring symptoms and local pollen levels.")
                .setSmallIcon(R.drawable.ic_alergo_notification)
                .setContentIntent(pi)
                .setOngoing(true)
                .setSilent(true)
                .build();
    }

    // ── Alert notification ────────────────────────────────────────────────────

    /**
     * Fires an urgent beeping alert notification.
     * Safe to call from any fragment or service.
     *
     * @param title   e.g. "⚠️ High grass pollen detected!"
     * @param message e.g. "Consider taking Cetirizine before going outside."
     */
    public static void sendAlert(Context ctx, String title, String message) {
        Intent openApp = new Intent(ctx, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                ctx, 0, openApp,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        Uri alertSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);

        Notification notification = new NotificationCompat.Builder(ctx, CHANNEL_ALERT)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                .setSmallIcon(R.drawable.ic_alergo_notification)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setSound(alertSound)
                .setVibrate(new long[]{0, 400, 200, 400, 200, 400})
                .setAutoCancel(true)    // dismisses itself when tapped
                .build();

        ctx.getSystemService(NotificationManager.class)
                .notify(NOTIF_ID_ALERT, notification);

        // Also vibrate immediately via Vibrator API as a backup
        // (some devices respect channel vibration, others need this)
        vibrateDevice(ctx);
    }

    public static void cancelAlert(Context ctx) {
        ctx.getSystemService(NotificationManager.class).cancel(NOTIF_ID_ALERT);
    }

    // ── Vibration ─────────────────────────────────────────────────────────────

    private static void vibrateDevice(Context ctx) {
        long[] pattern = {0, 400, 200, 400, 200, 400};
        try {
            // API 31+
            VibratorManager vm = (VibratorManager) ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            if (vm != null) {
                vm.getDefaultVibrator().vibrate(
                        VibrationEffect.createWaveform(pattern, -1)
                );
                return;
            }
        } catch (Exception ignored) {}

        // Fallback for API < 31
        Vibrator v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null && v.hasVibrator()) {
            v.vibrate(VibrationEffect.createWaveform(pattern, -1));
        }
    }

    // ── Quick test helper — call from HomeFragment to verify everything works ──

    /**
     * Fires a test alert immediately. Hook this to a button while developing.
     * Remove before release.
     */
    public static void sendTestAlert(Context ctx) {
        sendAlert(
                ctx,
                "⚠️ Test alert — AlergoGuard",
                "This is a test. Grass pollen is very high near you. Consider staying indoors."
        );
    }
}