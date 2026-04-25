package com.example.alergoguard.audio;

import android.app.*;
import android.content.Intent;
import android.media.*;
import android.os.IBinder;
import android.util.Log;
import androidx.core.app.NotificationCompat;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import com.example.alergoguard.MainActivity;

public class SneezeDetectorService extends Service {

    private static final String TAG = "SneezeDetectorService";

    private static final String CH_MONITOR = "alergo_monitor";
    private static final String CH_ALERT   = "alergo_alert";
    private static final int    ID_MONITOR = 10;
    private static final int    ID_ALERT   = 11;

    // Lower threshold + only 1 consecutive frame needed to reduce missed sneezes
    private static final float SNEEZE_THRESHOLD  = 0.05f;
    private static final int   FRAMES_TO_CONFIRM = 1;

    public static final String ACTION_SNEEZE = "com.example.alergoguard.SNEEZE";
    public static final String ACTION_SCORE  = "com.example.alergoguard.SCORE";
    public static final String EXTRA_SCORE   = "score";
    public static final String EXTRA_LABEL   = "label";

    private AudioRecord audioRecord;
    private AudioClassifier classifier;
    private Thread detectionThread;
    private volatile boolean running = false;
    private int consecutiveFrames = 0;

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
                    // Convert PCM short → normalized float [-1, 1]
                    for (int i = 0; i < AudioClassifier.FRAME_LENGTH; i++) {
                        floats[i] = pcm[i] / 32768.0f;
                    }

                    float[] scores = classifier.classify(floats);

                    // Use max(sneeze, cough) so either class triggers detection
                    float sneezeScore = AudioClassifier.sneezeScore(scores);

                    // Broadcast raw score to HomeFragment for the live readout
                    Intent scoreIntent = new Intent(ACTION_SCORE);
                    scoreIntent.putExtra(EXTRA_SCORE, sneezeScore);
                    LocalBroadcastManager.getInstance(this).sendBroadcast(scoreIntent);

                    if (sneezeScore >= SNEEZE_THRESHOLD) {
                        consecutiveFrames++;
                        Log.d(TAG, "Candidate frame " + consecutiveFrames + "/" + FRAMES_TO_CONFIRM
                                + "  score=" + sneezeScore);
                        if (consecutiveFrames >= FRAMES_TO_CONFIRM) {
                            triggerAlert(sneezeScore);
                            consecutiveFrames = 0;
                        }
                    } else {
                        consecutiveFrames = 0;
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

    private void triggerAlert(float score) {
        Log.i(TAG, "Sneeze/cough detected! score=" + score);

        Notification n = new NotificationCompat.Builder(this, CH_ALERT)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Sneeze detected!")
                .setContentText(String.format("Confidence: %.0f%%", score * 100))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build();
        getSystemService(NotificationManager.class).notify(ID_ALERT, n);

        Intent i = new Intent(ACTION_SNEEZE);
        i.putExtra(EXTRA_SCORE, score);
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
                .setContentText("Listening for sneezes…")
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }
}