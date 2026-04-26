package com.example.alergoguard.services;

import android.content.Context;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.speech.tts.TextToSpeech;

import java.util.Locale;

public class RiskAlertManager {

    private final TextToSpeech tts;
    private final ToneGenerator toneGenerator;
    private boolean ttsReady = false;
    private long lastWarningSpeechAtMs = 0L;
    private String lastWarningSpeechText = "";

    public RiskAlertManager(Context appContext) {
        toneGenerator = new ToneGenerator(AudioManager.STREAM_ALARM, 85);

        TextToSpeech[] ref = new TextToSpeech[1];
        ref[0] = new TextToSpeech(appContext, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int langStatus = ref[0].setLanguage(Locale.US);
                ttsReady = (langStatus != TextToSpeech.LANG_MISSING_DATA
                        && langStatus != TextToSpeech.LANG_NOT_SUPPORTED);
            } else {
                ttsReady = false;
            }
        });
        tts = ref[0];
    }

    public void handleRisk(String rawRiskLevel, String advice) {
        String risk = normalizeRiskLevel(rawRiskLevel);
        switch (risk) {
            case "critical":
                playDangerTone();
                stopSpeech();
                break;
            case "warning":
                speakWarning(advice);
                break;
            default:
                stopSpeech();
                break;
        }
    }

    /** Call this before a new warning arrives to bypass the 45s dedup guard. */
    public void resetSpeechDedup() {
        lastWarningSpeechAtMs = 0L;
        lastWarningSpeechText = "";
    }

    public void stopSpeech() {
        tts.stop();
    }

    public void release() {
        tts.stop();
        tts.shutdown();
        toneGenerator.release();
    }

    private void speakWarning(String advice) {
        if (!ttsReady || advice == null || advice.trim().isEmpty()) return;

        long now = System.currentTimeMillis();
        String normalized = advice.trim();
        if (normalized.equals(lastWarningSpeechText) && now - lastWarningSpeechAtMs < 45_000L) {
            return;
        }

        lastWarningSpeechAtMs = now;
        lastWarningSpeechText = normalized;
        tts.speak("Pollen warning. " + normalized, TextToSpeech.QUEUE_FLUSH, null, "risk-warning");
    }

    private void playDangerTone() {
        toneGenerator.startTone(ToneGenerator.TONE_CDMA_ABBR_ALERT, 220);
    }

    private String normalizeRiskLevel(String rawLevel) {
        if (rawLevel == null) return "normal";
        String level = rawLevel.toLowerCase(Locale.US);
        if ("critical".equals(level) || "high".equals(level)) return "critical";
        if ("warning".equals(level) || "medium".equals(level)) return "warning";
        return "normal";
    }
}