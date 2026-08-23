package com.opentypeless.android.ime;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;

import com.opentypeless.android.R;

/** Small, decorative voice-state indicator used by the IME status toolbar. */
final class VoicePulseView extends View {
    enum Phase { IDLE, PREPARING, LISTENING, PROCESSING }

    private static final int BAR_COUNT = 5;
    private static final float[] IDLE_BAR_HEIGHTS = {.18f, .34f, .24f, .42f, .20f};
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Phase phase = Phase.IDLE;

    VoicePulseView(Context context) {
        this(context, context.getColor(R.color.ime_primary));
    }

    VoicePulseView(Context context, int color) {
        super(context);
        paint.setColor(color);
        paint.setStrokeCap(Paint.Cap.ROUND);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void setPhase(Phase phase) {
        Phase safePhase = phase == null ? Phase.IDLE : phase;
        if (this.phase == safePhase) return;
        this.phase = safePhase;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        if (width <= 0f || height <= 0f) return;

        float barWidth = Math.max(2f, width / 15f);
        float gap = (width - BAR_COUNT * barWidth) / (BAR_COUNT + 1f);
        paint.setStrokeWidth(barWidth);
        long now = SystemClock.uptimeMillis();
        for (int index = 0; index < BAR_COUNT; index++) {
            float normalizedHeight = barHeight(index, now);
            float half = Math.max(barWidth, height * normalizedHeight * .5f);
            float x = gap + barWidth * .5f + index * (barWidth + gap);
            canvas.drawLine(x, height * .5f - half, x, height * .5f + half, paint);
        }
        if (phase != Phase.IDLE && isAttachedToWindow()) postInvalidateOnAnimation();
    }

    private float barHeight(int index, long now) {
        if (phase == Phase.IDLE) {
            return IDLE_BAR_HEIGHTS[index];
        }
        double speed = phase == Phase.PREPARING ? 330d : phase == Phase.PROCESSING ? 430d : 190d;
        double wave = Math.sin(now / speed + index * 1.17d);
        float floor = phase == Phase.LISTENING ? .22f : .14f;
        float amplitude = phase == Phase.LISTENING ? .56f : .34f;
        return floor + amplitude * (float) ((wave + 1d) * .5d);
    }
}
