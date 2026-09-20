package ai.mercan.app;

/** Thin JNI surface over the shared MercanChat C++ engine. */
public final class MercanNative {
    static {
        System.loadLibrary("mercan_jni");
    }

    public interface Callback {
        /** Called on the generation thread with a UTF-8 safe fragment. Return false to cancel. */
        boolean onPiece(String piece);
        void onDone(int genTokens, double tokPerSec, int promptTokens, double promptMs, int ctxUsed, int ctxSize);
    }

    private MercanNative() {}

    public static native long create();
    public static native void destroy(long handle);
    public static native void initBackends(String nativeLibDir);
    public static native String backendName();
    /** Returns null on success, otherwise an error message. */
    public static native String load(long handle, String path, int threads, int ctx);
    /** Rebuilds the KV cache from a saved transcript; returns null on success. */
    public static native String restore(long handle, String[] roles, String[] texts, int reserveTokens);
    /** Applies on the next reset/restore; callers re-prefill the open conversation. */
    public static native void setSystemPrompt(long handle, String prompt);
    public static native void unload(long handle);
    public static native void reset(long handle);
    public static native void cancel(long handle);
    public static native int threads(long handle);
    public static native int defaultThreads();
    /** True when this build bundles whisper.cpp. */
    public static native boolean sttAvailable();
    /** Points speech-to-text at a whisper.cpp model; the model loads on the first transcription. */
    public static native void sttInit(String modelPath, int threads);
    /** Blocking; 16 kHz mono samples in [-1,1]. Returns null when nothing was recognised. */
    public static native String sttTranscribe(float[] pcm, String language);

    /** Blocking; returns null on success, otherwise an error message. */
    public static native String chat(long handle, String message, float temperature, float topP, int topK,
                                     float repeatPenalty, int maxTokens, String assistantPrefix, Callback callback);
}
