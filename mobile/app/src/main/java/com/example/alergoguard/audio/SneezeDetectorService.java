package com.example.alergoguard.audio;

import android.app.*;
import android.content.Intent;
import android.media.*;
import android.os.IBinder;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import com.example.alergoguard.MainActivity;
import com.example.alergoguard.network.ApiClient;
import com.example.alergoguard.network.dto.SymptomLogRequest;
import com.example.alergoguard.network.dto.SymptomLogResponse;
import com.example.alergoguard.network.dto.YamNetEventRequest;
import com.example.alergoguard.network.dto.YamNetEventResponse;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class SneezeDetectorService extends Service {

    private static final String TAG = "SneezeDetectorService";

    private static final String CH_MONITOR = "alergo_monitor";
    private static final String CH_ALERT   = "alergo_alert";
    private static final int    ID_MONITOR = 10;
    private static final int    ID_ALERT   = 11;

    private static final float SNEEZE_THRESHOLD  = 0.005f;
    private static final float COUGH_THRESHOLD   = 0.05f;
    private static final int   FRAMES_TO_CONFIRM = 1;
    private static final long  COOLDOWN_MS       = 8_000L;

    public static final String ACTION_SNEEZE = "com.example.alergoguard.SNEEZE";
    public static final String ACTION_SCORE  = "com.example.alergoguard.SCORE";
    public static final String EXTRA_SCORE   = "score";
    public static final String EXTRA_LABEL   = "label";

    private AudioRecord      audioRecord;
    private AudioClassifier  classifier;
    private Thread           detectionThread;
    private volatile boolean running = false;

    private int  consecutiveSneezeFrames = 0;
    private int  consecutiveCoughFrames  = 0;
    private long lastSneezeFiredAt       = 0L;
    private long lastCoughFiredAt        = 0L;

    private static final String USER_ID = "marko_petrovic";
    private double currentLat    = 0.0;
    private double currentLng    = 0.0;
    public void setLocation(double lat, double lng) { this.currentLat = lat; this.currentLng = lng; }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        classifier = new AudioClassifier(this);
        createChannels();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startForeground(ID_MONITOR, buildMonitorNotif());
        } catch (Exception e) {
            Log.e(TAG, "startForeground failed: " + e.getMessage());
            stopSelf();
            return START_NOT_STICKY;
        }
        startLoop();
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onDestroy() {
        running = false;
        if (audioRecord != null) { audioRecord.stop(); audioRecord.release(); }
        classifier.close();
        super.onDestroy();
    }

    // ── Detection loop ────────────────────────────────────────────────────────

    private void startLoop() {
        running = true;

        int bufSize = Math.max(
                AudioRecord.getMinBufferSize(AudioClassifier.SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
                AudioClassifier.FRAME_LENGTH * 2);

        audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                AudioClassifier.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize);

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed");
            return;
        }

        audioRecord.startRecording();

        detectionThread = new Thread(() -> {
            short[] pcm    = new short[AudioClassifier.FRAME_LENGTH];
            float[] floats = new float[AudioClassifier.FRAME_LENGTH];
            int filled = 0;

            while (running) {
                int read = audioRecord.read(pcm, filled, AudioClassifier.FRAME_LENGTH - filled);
                if (read < 0) { Log.e(TAG, "AudioRecord read error: " + read); continue; }

                filled += read;

                if (filled >= AudioClassifier.FRAME_LENGTH) {
                    for (int i = 0; i < AudioClassifier.FRAME_LENGTH; i++)
                        floats[i] = pcm[i] / 32768.0f;

                    float[] scores     = classifier.classify(floats);
                    float   sneezeOnly = scores[AudioClassifier.SNEEZE_INDEX];
                    float   coughOnly  = scores[AudioClassifier.COUGH_INDEX]
                            + scores[AudioClassifier.THROAT_CLEAR_INDEX];

                    broadcastScore(sneezeOnly + coughOnly);

                    long now = System.currentTimeMillis();
                    checkAndFire(sneezeOnly, SNEEZE_THRESHOLD, "Sneeze", now);
                    checkAndFire(coughOnly,  COUGH_THRESHOLD,  "Cough",  now);

                    // 50% overlap
                    int half = AudioClassifier.FRAME_LENGTH / 2;
                    System.arraycopy(pcm, half, pcm, 0, half);
                    filled = half;
                }
            }
        }, "sneeze-thread");

        detectionThread.start();
    }

    private void checkAndFire(float score, float threshold, String label, long now) {
        boolean isSneeze = label.equals("Sneeze");

        if (score >= threshold) {
            int frames = isSneeze ? ++consecutiveSneezeFrames : ++consecutiveCoughFrames;
            Log.d(TAG, label + " candidate " + frames + "/" + FRAMES_TO_CONFIRM + "  score=" + score);

            if (frames >= FRAMES_TO_CONFIRM) {
                long lastFired = isSneeze ? lastSneezeFiredAt : lastCoughFiredAt;
                if (now - lastFired >= COOLDOWN_MS) {
                    if (isSneeze) lastSneezeFiredAt = now;
                    else          lastCoughFiredAt  = now;
                    triggerAlert(score, label);
                } else {
                    Log.d(TAG, label + " suppressed — " + (COOLDOWN_MS - (now - lastFired)) + "ms left");
                }
                if (isSneeze) consecutiveSneezeFrames = 0;
                else          consecutiveCoughFrames  = 0;
            }
        } else {
            if (isSneeze) consecutiveSneezeFrames = 0;
            else          consecutiveCoughFrames  = 0;
        }
    }

    // ── Alert ─────────────────────────────────────────────────────────────────

    private void triggerAlert(float score, String label) {
        Log.i(TAG, label + " detected! score=" + score);

        String type;
        if      (label.equals("Sneeze")) type = "sneeze";
        else if (label.equals("Cough"))  type = "cough";
        else                             type = "throat_clear";

        Log.d(TAG, "→ POST symptoms/log  userId=" + USER_ID
                + "  type=" + type
                + "  lat=" + currentLat
                + "  lng=" + currentLng);

        ApiClient.getService().sendYamNetEvent(
                new YamNetEventRequest("marko_petrovic", label.toLowerCase(), score, 44.8176, 20.4569)
        ).enqueue(new Callback<YamNetEventResponse>() {
            @Override
            public void onResponse(Call<YamNetEventResponse> call, Response<YamNetEventResponse> response) {
                if (response.body() == null) {
                    Log.w(TAG, "Backend response body is null");
                    return;
                }

                String alarmLevel = response.body().alarmLevel;
                Log.i(TAG, "Backend response: " + alarmLevel);

                if (alarmLevel == null || alarmLevel.equals("none")) return;

                // ✅ Build advice text based on alarm level
                String advice;
                if ("critical".equals(alarmLevel) || "high".equals(alarmLevel)) {
                    advice = "Danger! Critical pollen levels detected. Take your medication immediately and move indoors.";
                } else {
                    advice = "Pollen warning. Elevated levels detected in your area. Consider taking antihistamines.";
                }

                // ✅ Broadcast to HomeFragment — this is what drives TTS and the pulse
                Intent broadcast = new Intent("com.example.alergoguard.YAMNET_RESPONSE");
                broadcast.putExtra("alarm", true);
                broadcast.putExtra("alarm_level", alarmLevel);
                broadcast.putExtra("advice", advice);
                LocalBroadcastManager.getInstance(SneezeDetectorService.this).sendBroadcast(broadcast);
            }

            @Override
            public void onFailure(Call<YamNetEventResponse> call, Throwable t) {
                Log.e(TAG, "Backend call failed: " + t.getMessage());
            }
        });

        String emoji = label.equals("Sneeze") ? "🤧" : "😮";
        getSystemService(NotificationManager.class).notify(ID_ALERT,
                new NotificationCompat.Builder(this, CH_ALERT)
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle(emoji + " " + label + " detected!")
                        .setContentText("High pollen area — consider your medication")
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true)
                        .build());

        Intent i = new Intent(ACTION_SNEEZE);
        i.putExtra(EXTRA_SCORE, score);
        i.putExtra(EXTRA_LABEL, label);
        LocalBroadcastManager.getInstance(this).sendBroadcast(i);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void broadcastScore(float combined) {
        Intent i = new Intent(ACTION_SCORE);
        i.putExtra(EXTRA_SCORE, combined);
        LocalBroadcastManager.getInstance(this).sendBroadcast(i);
    }

    private void createChannels() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CH_MONITOR, "Allergen Monitor", NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(
                CH_ALERT, "Sneeze Alerts", NotificationManager.IMPORTANCE_HIGH));
    }

    private Notification buildMonitorNotif() {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CH_MONITOR)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("AlergoGuard active")
                .setContentText("Listening for sneezes and coughs…")
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }
}