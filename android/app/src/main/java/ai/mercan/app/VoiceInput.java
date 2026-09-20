package ai.mercan.app;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Records 16 kHz mono audio and turns it into text with the bundled whisper.cpp model.
 * Everything stays on the device.
 */
public final class VoiceInput {
    public interface Listener {
        /** Loudest sample of the latest block (0..1), on the UI thread. */
        void onLevel(float peak);
        /** Seconds recorded so far, on the UI thread. */
        void onTick(int seconds);
        /** Transcription finished; text is null when nothing was recognised. */
        void onResult(String text);
        void onError(String message);
    }

    private static final String TAG = "MercanVoice";
    private static final String ASSET = "whisper.bin";
    private static final int RATE = 16000;
    private static final int MAX_SECONDS = 180;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Thread thread;
    private volatile boolean recording = false;
    private volatile boolean cancelled = false;

    public VoiceInput(Context context) {
        this.context = context.getApplicationContext();
    }

    public boolean isRecording() { return recording; }

    /** Copies the model out of the APK once; returns its path (slow on first call). */
    private File modelFile() throws IOException {
        final File out = new File(context.getFilesDir(), ASSET);
        if (out.exists() && out.length() > 1_000_000L) return out;
        final File tmp = new File(context.getFilesDir(), ASSET + ".part");
        try (InputStream in = context.getAssets().open(ASSET);
             OutputStream os = new FileOutputStream(tmp)) {
            final byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
        if (!tmp.renameTo(out)) throw new IOException("model taşınamadı");
        return out;
    }

    public void start(Listener listener) {
        if (recording) return;
        recording = true;
        cancelled = false;
        thread = new Thread(() -> run(listener), "mercan-voice");
        thread.start();
    }

    /** Stops recording and transcribes what was captured. */
    public void stop() { recording = false; }

    /** Stops recording and throws the audio away. */
    public void cancel() {
        cancelled = true;
        recording = false;
    }

    private void run(Listener listener) {
        AudioRecord record = null;
        try {
            final int minBuffer = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            final int bufferSize = Math.max(minBuffer, RATE); // ~0.5 s of headroom
            record = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                fail(listener, "mikrofon açılamadı");
                return;
            }
            record.startRecording();

            final List<short[]> blocks = new ArrayList<>();
            final short[] buf = new short[1024];
            int total = 0, lastSecond = -1;
            while (recording && total < RATE * MAX_SECONDS) {
                final int n = record.read(buf, 0, buf.length);
                if (n <= 0) continue;
                short peak = 0;
                for (int i = 0; i < n; i++) {
                    final short v = (short) Math.abs(buf[i]);
                    if (v > peak) peak = v;
                }
                final float level = peak / 32768f;
                main.post(() -> listener.onLevel(level));

                final short[] block = new short[n];
                System.arraycopy(buf, 0, block, 0, n);
                blocks.add(block);
                total += n;

                final int seconds = total / RATE;
                if (seconds != lastSecond) {
                    lastSecond = seconds;
                    main.post(() -> listener.onTick(seconds));
                }
            }
            record.stop();

            if (cancelled) return;
            if (total < RATE / 3) {   // shorter than ~0.3 s
                main.post(() -> listener.onResult(null));
                return;
            }

            final float[] pcm = new float[total];
            int at = 0;
            for (short[] block : blocks) {
                for (short v : block) pcm[at++] = v / 32768f;
            }

            MercanNative.sttInit(modelFile().getAbsolutePath(), Math.max(2, MercanNative.defaultThreads()));
            final String text = MercanNative.sttTranscribe(pcm, "tr");
            main.post(() -> listener.onResult(text));
        } catch (Throwable t) {
            Log.e(TAG, "voice input failed", t);
            fail(listener, t.getMessage() == null ? t.toString() : t.getMessage());
        } finally {
            recording = false;
            if (record != null) {
                try { record.release(); } catch (Throwable ignored) {}
            }
        }
    }

    private void fail(Listener listener, String message) {
        main.post(() -> listener.onError(message));
    }
}
