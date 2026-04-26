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

    // Detection thresholds
    private static final float SNEEZE_THRESHOLD  = 0.005f;
    private static final float COUGH_THRESHOLD   = 0.05f;
    private static final int   FRAMES_TO_CONFIRM = 1;

    // After firing an alert, ignore the same type for this many milliseconds.
    // A sneeze lasts ~1s; 8s cooldown means one real sneeze = one notification.
    private static final long SNEEZE_COOLDOWN_MS = 8_000L;
    private static final long COUGH_COOLDOWN_MS  = 8_000L;

    public static final String ACTION_SNEEZE = "com.example.alergoguard.SNEEZE";
    public static final String ACTION_SCORE  = "com.example.alergoguard.SCORE";
    public static final String EXTRA_SCORE   = "score";
    public static final String EXTRA_LABEL   = "label";

    private AudioRecord audioRecord;
    private AudioClassifier classifier;
    private Thread detectionThread;
    private volatile boolean running = false;

    // Consecutive-frame counters
    private int consecutiveSneezeFrames = 0;
    private int consecutiveCoughFrames  = 0;

    // Cooldown timestamps — 0 means "never fired yet, always allowed"
    private long lastSneezeFiredAt = 0L;
    private long lastCoughFiredAt  = 0L;

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

    private void startLoop() {
        running = true;

        int minBuf  = AudioRecord.getMinBufferSize(
                AudioClassifier.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        int bufSize = Math.max(minBuf, AudioClassifier.FRAME_LENGTH * 2);

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
                int toRead = AudioClassifier.FRAME_LENGTH - filled;
                int read   = audioRecord.read(pcm, filled, toRead);

                if (read < 0) {
                    Log.e(TAG, "AudioRecord read error: " + read);
                    continue;
                }

                filled += read;

                if (filled >= AudioClassifier.FRAME_LENGTH) {
                    for (int i = 0; i < AudioClassifier.FRAME_LENGTH; i++) {
                        floats[i] = pcm[i] / 32768.0f;
                    }

                    float[] scores = classifier.classify(floats);

                    float sneezeOnly = scores[AudioClassifier.SNEEZE_INDEX];
                    float coughOnly  = scores[AudioClassifier.COUGH_INDEX]
                            + scores[AudioClassifier.THROAT_CLEAR_INDEX];
                    float combined   = sneezeOnly + coughOnly;

                    Log.d(TAG, String.format(
                            "sneeze=%.3f (thr=%.3f)  cough=%.3f (thr=%.2f)",
                            sneezeOnly, SNEEZE_THRESHOLD,
                            coughOnly,  COUGH_THRESHOLD));

                    // Broadcast live score to HomeFragment
                    Intent scoreIntent = new Intent(ACTION_SCORE);
                    scoreIntent.putExtra(EXTRA_SCORE, combined);
                    LocalBroadcastManager.getInstance(this).sendBroadcast(scoreIntent);

                    long now = System.currentTimeMillis();

                    // ── Sneeze check ──────────────────────────────────────────
                    if (sneezeOnly >= SNEEZE_THRESHOLD) {
                        consecutiveSneezeFrames++;
                        Log.d(TAG, "Sneeze candidate " + consecutiveSneezeFrames
                                + "/" + FRAMES_TO_CONFIRM + "  score=" + sneezeOnly);
                        if (consecutiveSneezeFrames >= FRAMES_TO_CONFIRM) {
                            // Only fire if we're past the cooldown window
                            if (now - lastSneezeFiredAt >= SNEEZE_COOLDOWN_MS) {
                                lastSneezeFiredAt = now;
                                triggerAlert(sneezeOnly, "Sneeze");
                            } else {
                                Log.d(TAG, "Sneeze suppressed — still in cooldown ("
                                        + (SNEEZE_COOLDOWN_MS - (now - lastSneezeFiredAt)) + "ms left)");
                            }
                            consecutiveSneezeFrames = 0;
                        }
                    } else {
                        consecutiveSneezeFrames = 0;
                    }

                    // ── Cough check ───────────────────────────────────────────
                    if (coughOnly >= COUGH_THRESHOLD) {
                        consecutiveCoughFrames++;
                        Log.d(TAG, "Cough candidate " + consecutiveCoughFrames
                                + "/" + FRAMES_TO_CONFIRM + "  score=" + coughOnly);
                        if (consecutiveCoughFrames >= FRAMES_TO_CONFIRM) {
                            if (now - lastCoughFiredAt >= COUGH_COOLDOWN_MS) {
                                lastCoughFiredAt = now;
                                triggerAlert(coughOnly, "Cough");
                            } else {
                                Log.d(TAG, "Cough suppressed — still in cooldown ("
                                        + (COUGH_COOLDOWN_MS - (now - lastCoughFiredAt)) + "ms left)");
                            }
                            consecutiveCoughFrames = 0;
                        }
                    } else {
                        consecutiveCoughFrames = 0;
                    }

                    // 50% overlap — keeps context between frames
                    int half = AudioClassifier.FRAME_LENGTH / 2;
                    System.arraycopy(pcm, half, pcm, 0, half);
                    filled = half;
                }
            }
        }, "sneeze-thread");

        detectionThread.start();
    }

    private void triggerAlert(float score, String label) {
        Log.i(TAG, label + " detected! score=" + score);

        String emoji = label.equals("Sneeze") ? "🤧" : "😮";

        // No confidence % in the notification text
        Notification n = new NotificationCompat.Builder(this, CH_ALERT)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(emoji + " " + label + " detected!")
                .setContentText("High pollen area — consider your medication")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build();
        getSystemService(NotificationManager.class).notify(ID_ALERT, n);

        ApiClient.getService().sendYamNetEvent(
                new YamNetEventRequest("test_user_1", label.toLowerCase(), score, 44.8176, 20.4569)
        ).enqueue(new Callback<YamNetEventResponse>() {
            @Override
            public void onResponse(Call<YamNetEventResponse> call, Response<YamNetEventResponse> response) {
                Log.i(TAG, "Backend response: " + (response.body() != null ? response.body().alarmLevel : "null"));
            }

            @Override
            public void onFailure(Call<YamNetEventResponse> call, Throwable t) {
                Log.e(TAG, "Backend call failed: " + t.getMessage());
            }
        });

        Intent i = new Intent(ACTION_SNEEZE);
        i.putExtra(EXTRA_SCORE, score);
        i.putExtra(EXTRA_LABEL, label);
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