package com.opentypeless.android.keyboard.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.widget.Button;

/** Button whose icon is a centred layer of its platform-rendered background. */
public final class CenteredIconButton extends Button {
    private int backgroundResource;
    private int centeredIconResource;
    private CenteredIconBackground centeredBackground;

    public CenteredIconButton(Context context) {
        super(context);
    }

    @Override
    public void setBackgroundResource(int resource) {
        backgroundResource = resource;
        if (centeredIconResource == 0) {
            centeredBackground = null;
            super.setBackgroundResource(resource);
        } else {
            rebuildLayeredBackground();
        }
    }

    public void setCenteredIconResource(int drawableResource) {
        if (getContext().getDrawable(drawableResource) == null) {
            throw new IllegalArgumentException("center icon resource is missing");
        }
        centeredIconResource = drawableResource;
        if (getText() != null && getText().length() != 0) setText("");
        setForeground(null);
        setCompoundDrawables(null, null, null, null);
        setCompoundDrawablePadding(0);
        rebuildLayeredBackground();
    }

    public Rect centeredIconBounds() {
        refreshBackgroundBounds(getWidth(), getHeight());
        if (centeredBackground == null) return new Rect();
        return centeredBackground.iconBounds();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        refreshBackgroundBounds(width, height);
    }

    private void rebuildLayeredBackground() {
        if (backgroundResource == 0 || centeredIconResource == 0) return;
        Drawable base = getContext().getDrawable(backgroundResource);
        Drawable icon = getContext().getDrawable(centeredIconResource);
        if (base == null || icon == null) {
            throw new IllegalArgumentException("icon button drawable resource is missing");
        }
        base = base.mutate();
        icon = icon.mutate();
        centeredBackground = new CenteredIconBackground(base, icon);
        super.setBackground(centeredBackground);
        refreshBackgroundBounds(getWidth(), getHeight());
    }

    private void refreshBackgroundBounds(int width, int height) {
        if (centeredBackground == null || width <= 0 || height <= 0) return;
        centeredBackground.setBounds(0, 0, width, height);
    }

    private static final class CenteredIconBackground extends Drawable
            implements Drawable.Callback {
        private final Drawable base;
        private final Drawable icon;

        CenteredIconBackground(Drawable base, Drawable icon) {
            this.base = base;
            this.icon = icon;
            base.setCallback(this);
            icon.setCallback(this);
        }

        Rect iconBounds() {
            return new Rect(icon.getBounds());
        }

        @Override
        public void draw(Canvas canvas) {
            base.draw(canvas);
            icon.draw(canvas);
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            base.setBounds(bounds);
            int iconWidth = Math.min(Math.max(1, icon.getIntrinsicWidth()), bounds.width());
            int iconHeight = Math.min(Math.max(1, icon.getIntrinsicHeight()), bounds.height());
            if ((iconWidth & 1) != (bounds.width() & 1)) iconWidth = Math.max(1, iconWidth - 1);
            if ((iconHeight & 1) != (bounds.height() & 1)) iconHeight = Math.max(1, iconHeight - 1);
            int left = bounds.left + (bounds.width() - iconWidth) / 2;
            int top = bounds.top + (bounds.height() - iconHeight) / 2;
            icon.setBounds(left, top, left + iconWidth, top + iconHeight);
        }

        @Override
        public boolean isStateful() {
            return base.isStateful() || icon.isStateful();
        }

        @Override
        protected boolean onStateChange(int[] state) {
            return base.setState(state) | icon.setState(state);
        }

        @Override
        public void setAlpha(int alpha) {
            base.setAlpha(alpha);
            icon.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            base.setColorFilter(colorFilter);
            icon.setColorFilter(colorFilter);
        }

        @SuppressWarnings("deprecation")
        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public boolean getPadding(Rect padding) {
            return base.getPadding(padding);
        }

        @Override
        public void invalidateDrawable(Drawable drawable) {
            invalidateSelf();
        }

        @Override
        public void scheduleDrawable(Drawable drawable, Runnable action, long when) {
            scheduleSelf(action, when);
        }

        @Override
        public void unscheduleDrawable(Drawable drawable, Runnable action) {
            unscheduleSelf(action);
        }
    }
}
