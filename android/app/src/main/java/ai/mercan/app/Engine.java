package ai.mercan.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Process-wide owner of the native model so configuration changes (dark mode, rotation) and
 * activity recreation never reload the 0.5 GB model. All native calls go through {@link #executor}.
 */
public final class Engine {
    public interface Listener {
        void onProgress(String label, int pct, boolean indeterminate);
        void onReady();
        void onError(String message);
    }

    private static Engine instance;

    public static synchronized Engine get() {
        if (instance == null) instance = new Engine();
        return instance;
    }

    public final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    public long handle = 0;
    public volatile boolean modelReady = false;
    public volatile boolean loading = false;
    public volatile boolean generating = false;
    public String backendLabel = "";
    private Listener listener;
    private String lastError;

    private Engine() {}

    public void setListener(Listener l) { listener = l; }

    public void init(Context ctx) {
        if (handle != 0) return;
        handle = MercanNative.create();
        MercanNative.initBackends(ctx.getApplicationInfo().nativeLibraryDir);
    }

    public String lastError() { return lastError; }

    /** Downloads (if needed) and loads the model; reports through the listener on the UI thread. */
    public void loadModel(Context appCtx, int threads, int ctxSize, String systemPrompt) {
        if (loading) return;
        loading = true;
        modelReady = false;
        lastError = null;
        executor.execute(() -> {
            try {
                if (!ModelManager.isReady(appCtx)) {
                    // A plaintext model from an older install (or dropped in by hand) is sealed first.
                    progress(appCtx.getString(R.string.securing), 0, true);
                    boolean migrated = ModelManager.migrateLegacy(appCtx, (got, total) -> {
                        int pct = total > 0 ? (int) (got * 100 / total) : 0;
                        progress(appCtx.getString(R.string.securing_pct, pct), pct, false);
                    });
                    if (!migrated) {
                        progress(appCtx.getString(R.string.downloading), 0, false);
                        ModelManager.download(appCtx, (got, total) -> {
                            int pct = total > 0 ? (int) (got * 100 / total) : 0;
                            progress(appCtx.getString(R.string.downloading_pct, pct, got >> 20), pct, false);
                        });
                    }
                }
                progress(appCtx.getString(R.string.unsealing), 0, false);
                File plain = ModelManager.prepareForLoad(appCtx, (got, total) -> {
                    int pct = total > 0 ? (int) (got * 100 / total) : 0;
                    progress(appCtx.getString(R.string.unsealing_pct, pct), pct, false);
                });
                progress(appCtx.getString(R.string.loading), 0, true);
                String err;
                try {
                    err = MercanNative.load(handle, plain.getAbsolutePath(), threads, ctxSize);
                } finally {
                    // The loader has mmapped the file; unlinking keeps the mapping but leaves no
                    // plaintext on disk.
                    plain.delete();
                }
                if (err != null) throw new RuntimeException(err);
                MercanNative.setSystemPrompt(handle, systemPrompt == null ? "" : systemPrompt);
                backendLabel = MercanNative.backendName();
                modelReady = true;
                loading = false;
                ui.post(() -> { if (listener != null) listener.onReady(); });
            } catch (Exception e) {
                loading = false;
                lastError = e.getMessage();
                ui.post(() -> { if (listener != null) listener.onError(e.getMessage()); });
            }
        });
    }

    private void progress(String label, int pct, boolean indeterminate) {
        ui.post(() -> { if (listener != null) listener.onProgress(label, pct, indeterminate); });
    }
}
