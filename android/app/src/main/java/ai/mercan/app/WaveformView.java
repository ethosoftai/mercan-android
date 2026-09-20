package ai.mercan.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * Vertical bars that flow to the left while recording, one bar per frame, sized by how loud
 * the microphone was since the previous frame.
 */
public class WaveformView extends View {
    private static final int BARS = 96;
    private static final float BAR_WIDTH_DP = 3f;
    private static final float GAP_DP = 3f;

    private final float[] levels = new float[BARS];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float pendingPeak = 0f;
    private boolean running = false;

    public WaveformView(Context context) { this(context, null); }

    public WaveformView(Context context, AttributeSet attrs) {
        super(context, attrs);
        paint.setColor(Color.WHITE);
        paint.setStyle(Paint.Style.FILL);
    }

    /** Feeds the loudest sample of the latest audio block (0..1). */
    public void push(float peak) {
        if (peak > pendingPeak) pendingPeak = peak;
    }

    public void start() {
        java.util.Arrays.fill(levels, 0f);
        pendingPeak = 0f;
        running = true;
        postInvalidateOnAnimation();
    }

    public void stop() {
        running = false;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final float density = getResources().getDisplayMetrics().density;
        final float barWidth = BAR_WIDTH_DP * density;
        final float step = (BAR_WIDTH_DP + GAP_DP) * density;
        final float height = getHeight();
        final float centerY = height / 2f;
        final float maxBar = height * 0.82f;
        final float minBar = barWidth;   // a dot when silent

        if (running) {
            System.arraycopy(levels, 1, levels, 0, BARS - 1);
            // Same curve as the desktop app so both feel alike.
            levels[BARS - 1] = Math.min(1f, (float) Math.pow(pendingPeak * 3.2f, 0.75f));
            pendingPeak = 0f;
        }

        final int visible = Math.min(BARS, (int) (getWidth() / step));
        float x = getWidth() - barWidth;
        for (int i = 0; i < visible; i++) {
            final float level = levels[BARS - 1 - i];
            final float barHeight = Math.max(minBar, level * maxBar);
            paint.setAlpha((int) (255 * (0.25f + 0.75f * (1f - (float) i / visible))));
            canvas.drawRoundRect(x, centerY - barHeight / 2f, x + barWidth, centerY + barHeight / 2f,
                    barWidth / 2f, barWidth / 2f, paint);
            x -= step;
            if (x < 0) break;
        }

        if (running) postInvalidateOnAnimation();
    }
}
