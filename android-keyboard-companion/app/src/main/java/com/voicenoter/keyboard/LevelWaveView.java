package com.voicenoter.keyboard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * Recording waveform: a row of rounded bars scrolling right-to-left, each the
 * microphone level at one sample. Fed by {@link #push(float)}; it shows real
 * input, so silence stays flat and speech visibly moves.
 */
final class LevelWaveView extends View {
    private static final int BAR_COUNT = 22;
    /** Shortest bar, as a share of the height, so silence still reads as a waveform. */
    private static final float FLOOR = 0.16f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float[] levels = new float[BAR_COUNT];
    private int head = 0;

    LevelWaveView(Context context) {
        super(context);
        paint.setStyle(Paint.Style.FILL);
    }

    void setBarColor(int color) {
        paint.setColor(color);
        invalidate();
    }

    void clear() {
        java.util.Arrays.fill(levels, 0f);
        invalidate();
    }

    /** Adds one sample, 0 (silence) … 1 (loud). */
    void push(float level) {
        levels[head] = Math.max(0f, Math.min(1f, level));
        head = (head + 1) % BAR_COUNT;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;
        float gap = w / BAR_COUNT * 0.42f;
        float barW = (w - gap * (BAR_COUNT - 1)) / BAR_COUNT;
        float radius = barW / 2f;
        for (int i = 0; i < BAR_COUNT; i++) {
            // Oldest sample on the left, newest on the right.
            float level = levels[(head + i) % BAR_COUNT];
            float barH = h * (FLOOR + (1f - FLOOR) * level);
            float left = i * (barW + gap);
            float top = (h - barH) / 2f;
            rect.set(left, top, left + barW, top + barH);
            canvas.drawRoundRect(rect, radius, radius, paint);
        }
    }
}
