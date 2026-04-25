package com.example.alergoguard.audio;

import android.content.Context;
import android.util.Log;
import org.tensorflow.lite.Interpreter;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

public class AudioClassifier {

    private static final String TAG = "AudioClassifier";

    public static final int SAMPLE_RATE    = 16000;
    public static final int FRAME_LENGTH   = 15600;

    public static final int SNEEZE_INDEX         = 44;
    public static final int COUGH_INDEX          = 42;
    public static final int THROAT_CLEAR_INDEX   = 43;

    private Interpreter tflite;
    private final List<String> labels = new ArrayList<>();

    public AudioClassifier(Context context) {
        try {
            tflite = new Interpreter(loadModelFile(context));
            loadLabels(context);
            Log.d(TAG, "YAMNet ready. Classes: " + labels.size());
        } catch (IOException e) {
            Log.e(TAG, "Failed to load model", e);
        }
    }

    /** Pass float[15600] mono 16kHz audio normalized to [-1,1]. Returns float[521] scores. */
    public float[] classify(float[] samples) {
        if (tflite == null) return new float[521];

        int inputSize  = tflite.getInputTensor(0).shape()[0];
        int outputSize = tflite.getOutputTensor(0).shape()[1];

        float[]   input  = new float[inputSize];
        float[][] output = new float[1][outputSize];

        int copyLen = Math.min(samples.length, inputSize);
        System.arraycopy(samples, 0, input, 0, copyLen);

        tflite.run(input, output);

        float[] scores = output[0];

        // Proper top-5
        int[]   topIdx    = new int[5];
        float[] topScores = new float[]{-1,-1,-1,-1,-1};
        for (int i = 0; i < scores.length; i++) {
            if (scores[i] > topScores[4]) {
                topScores[4] = scores[i];
                topIdx[4]    = i;
                for (int j = 3; j >= 0; j--) {
                    if (topScores[j+1] > topScores[j]) {
                        float tmpS = topScores[j]; topScores[j] = topScores[j+1]; topScores[j+1] = tmpS;
                        int   tmpI = topIdx[j];   topIdx[j]   = topIdx[j+1];   topIdx[j+1]   = tmpI;
                    } else break;
                }
            }
        }

        float combined = sneezeScore(scores);
        Log.d(TAG, String.format(
                "COMBINED=%.3f  sneeze=%.3f  cough=%.3f  throat=%.3f  |  top5: %d=%.3f  %d=%.3f  %d=%.3f  %d=%.3f  %d=%.3f",
                combined,
                scores[SNEEZE_INDEX], scores[COUGH_INDEX], scores[THROAT_CLEAR_INDEX],
                topIdx[0], topScores[0], topIdx[1], topScores[1],
                topIdx[2], topScores[2], topIdx[3], topScores[3],
                topIdx[4], topScores[4]));

        return scores;
    }

    /**
     * Combined score: sneeze + cough + throat-clearing.
     * Summing instead of max because the model often splits the score
     * across all three when a real sneeze/cough happens in a noisy room.
     */
    public static float sneezeScore(float[] scores) {
        return scores[SNEEZE_INDEX] + scores[COUGH_INDEX] + scores[THROAT_CLEAR_INDEX];
    }

    public String getLabel(int index) {
        return (index >= 0 && index < labels.size()) ? labels.get(index) : "Unknown";
    }

    public void close() {
        if (tflite != null) tflite.close();
    }

    private MappedByteBuffer loadModelFile(Context ctx) throws IOException {
        var afd = ctx.getAssets().openFd("yamnet.tflite");
        try (FileInputStream fis = new FileInputStream(afd.getFileDescriptor())) {
            return fis.getChannel().map(
                    FileChannel.MapMode.READ_ONLY,
                    afd.getStartOffset(),
                    afd.getDeclaredLength()
            );
        }
    }

    private void loadLabels(Context ctx) throws IOException {
        BufferedReader r = new BufferedReader(
                new InputStreamReader(ctx.getAssets().open("yamnet_class_map.csv")));
        String line;
        boolean first = true;
        while ((line = r.readLine()) != null) {
            if (first) { first = false; continue; }
            String[] parts = line.split(",");
            if (parts.length >= 3) labels.add(parts[2].trim());
        }
        r.close();
    }
}