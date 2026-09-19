package ai.mercan.app;

import android.os.Handler;

/**
 * Reveals streamed text character by character at a steady pace, speeding up when the model
 * gets ahead so the display never lags far behind generation.
 */
public final class Typewriter {
    public interface Listener {
        /** Called on the UI thread with the visible prefix; {@code done} once everything is shown. */
        void onReveal(String visible, boolean done);
    }

    private static final long TICK_MS = 16;

    private final Handler ui;
    private final Listener listener;
    private final StringBuilder target = new StringBuilder();
    private final Object lock = new Object();
    private boolean sourceDone = false;
    private boolean enabled = true;
    private boolean running = false;
    private int shown = 0;

    public Typewriter(Handler ui, Listener listener) {
        this.ui = ui;
        this.listener = listener;
    }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    /** Any thread. */
    public void append(String piece) {
        synchronized (lock) { target.append(piece); }
        ui.post(this::ensureRunning);
    }

    /** Any thread: generation finished; the ticker drains what is left and reports done. */
    public void finish() {
        synchronized (lock) { sourceDone = true; }
        ui.post(this::ensureRunning);
    }

    private void ensureRunning() {
        if (running) return;
        running = true;
        ui.post(tick);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            final String snapshot;
            final boolean done;
            synchronized (lock) { snapshot = target.toString(); done = sourceDone; }
            int backlog = snapshot.length() - shown;
            if (backlog > 0) {
                int step;
                if (!enabled) step = backlog;
                else {
                    // ~2 chars per frame (~120 chars/s) with a proportional catch-up term.
                    step = 2 + backlog / 40;
                    // never split a surrogate pair
                    if (shown + step < snapshot.length() && Character.isHighSurrogate(snapshot.charAt(shown + step - 1))) step++;
                }
                shown = Math.min(snapshot.length(), shown + step);
                listener.onReveal(snapshot.substring(0, shown), false);
            }
            if (shown >= snapshot.length() && done) {
                running = false;
                listener.onReveal(snapshot, true);
                return;
            }
            ui.postDelayed(this, TICK_MS);
        }
    };
}
