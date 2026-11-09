package ai.mercan.app;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Process-wide runner for model downloads/imports, so a transfer keeps going when the user
 * leaves the Models screen. One task at a time; state is polled by whichever screen listens.
 */
final class ModelTasks {
    interface Listener { void onChanged(); }

    interface Task {
        /** Runs off the UI thread; report progress through {@code progress}. */
        void run(ModelManager.Progress progress, AtomicBoolean cancel) throws Exception;
    }

    private static final ModelTasks INSTANCE = new ModelTasks();
    static ModelTasks get() { return INSTANCE; }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Listener listener;

    // State read on the UI thread.
    volatile boolean running;
    volatile String label = "";
    volatile long done;
    volatile long total;
    /** Result of the last finished task: null = success, otherwise a user-facing error. */
    volatile String lastError;
    volatile boolean lastFinished;
    private volatile AtomicBoolean cancel = new AtomicBoolean(false);

    private ModelTasks() {}

    void setListener(Listener l) { listener = l; }

    /** @return false when another task is already running. */
    boolean start(String taskLabel, Task task) {
        if (running) return false;
        running = true;
        label = taskLabel;
        done = 0;
        total = -1;
        lastError = null;
        lastFinished = false;
        cancel = new AtomicBoolean(false);
        AtomicBoolean myCancel = cancel;
        notifyChanged();
        executor.execute(() -> {
            String error = null;
            try {
                task.run((d, t) -> { done = d; total = t; notifyChanged(); }, myCancel);
            } catch (Exception e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            lastError = error;
            lastFinished = true;
            running = false;
            notifyChanged();
        });
        return true;
    }

    void cancel() { cancel.set(true); }

    private void notifyChanged() {
        ui.post(() -> { if (listener != null) listener.onChanged(); });
    }
}
