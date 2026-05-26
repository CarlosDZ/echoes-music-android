package com.musica.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.Nullable;

import com.google.android.material.R;

import java.util.Random;

/**
 * Decorative animated equalizer: bars wobble while playing and settle flat when
 * paused. Not a real audio visualizer (that needs the RECORD_AUDIO permission) —
 * it just fills space and signals "playing". Driven by its own frame loop.
 */
public class EqualizerView extends View {

    private static final int BARS = 9;
    private static final long FRAME_MS = 40;

    private final float[] heights = new float[BARS];   // 0..1
    private final float[] targets = new float[BARS];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Random random = new Random();

    private boolean active = false;
    private boolean looping = false;

    private final Runnable frame = new Runnable() {
        @Override public void run() {
            boolean moved = step();
            invalidate();
            if (active || moved) {
                postDelayed(this, FRAME_MS);
            } else {
                looping = false;   // settled and paused → stop the loop
            }
        }
    };

    public EqualizerView(Context c, @Nullable AttributeSet a) {
        super(c, a);
        paint.setColor(resolveColor(c, R.attr.colorPrimary, 0xFFB14EFF));
        for (int i = 0; i < BARS; i++) {
            heights[i] = 0.1f;
            targets[i] = 0.1f;
        }
    }

    public void setActive(boolean active) {
        this.active = active;
        if (active && !looping) {
            looping = true;
            post(frame);
        }
    }

    /** Advances bar heights toward their targets; returns true if anything moved. */
    private boolean step() {
        boolean moved = false;
        for (int i = 0; i < BARS; i++) {
            if (active && Math.abs(heights[i] - targets[i]) < 0.04f) {
                targets[i] = 0.2f + random.nextFloat() * 0.8f;
            } else if (!active) {
                targets[i] = 0.08f;
            }
            float delta = (targets[i] - heights[i]) * 0.35f;
            if (Math.abs(delta) > 0.001f) {
                heights[i] += delta;
                moved = true;
            }
        }
        return moved;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        float gap = w / (float) (BARS * 2 + 1);
        float barW = gap;
        float radius = barW / 2f;
        float maxH = h * 0.9f;

        float x = gap;
        for (int i = 0; i < BARS; i++) {
            float barH = Math.max(barW, heights[i] * maxH);
            float top = (h - barH) / 2f;
            rect.set(x, top, x + barW, top + barH);
            canvas.drawRoundRect(rect, radius, radius, paint);
            x += gap * 2;
        }
    }

    private static int resolveColor(Context c, int attr, int fallback) {
        TypedValue tv = new TypedValue();
        if (c.getTheme().resolveAttribute(attr, tv, true)) return tv.data;
        return fallback;
    }
}
