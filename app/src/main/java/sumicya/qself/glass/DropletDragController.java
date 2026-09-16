/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.glass;

import android.os.SystemClock;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;

import java.lang.ref.WeakReference;

/**
 * Press and horizontal-drag behaviour of the selected-tab droplet, mirroring
 * Kyant0/AndroidLiquidGlass {@code LiquidBottomTabs} (Apache-2.0, see
 * app/NOTICE): five springs carry position (tab units), normalised drag
 * velocity for the stretch, press progress and the two press scales; a
 * parallax spring eases the whole row at most 4dp while dragging; the held
 * container grows by 16dp and every tab glyph scales to 1.2x. The gesture is
 * not claimed until the finger crosses the horizontal touch slop, so
 * ordinary tab taps keep reaching the host.
 */
final class DropletDragController implements LiquidGlassHostLayout.DragHandler {

    private static final float PRESSED_RATIO = 78f / 56f;
    private static final float TAB_PRESS_SCALE = 1.2f;
    private static final float MAX_STRETCH = 0.2f;
    private static final float SETTLE_TOLERANCE = 0.025f;
    /** Container growth while held, per upstream (16dp spread across the pill). */
    private static final float HOST_GROWTH_DP = 16f;
    /** Whole-row parallax while dragging, per upstream. */
    private static final float PARALLAX_DP = 4f;

    private final WeakReference<View> dropletRef;
    private WeakReference<ViewGroup> rowRef;
    private WeakReference<View> pillRef = new WeakReference<>(null);

    private final int touchSlop;
    private final float density;

    private final Spring positionSpring;
    private final Spring velocitySpring;
    private final Spring pressSpring;
    private final Spring scaleXSpring;
    private final Spring scaleYSpring;
    private final Spring parallaxSpring;

    private float downX;
    private float downY;
    private float lastX;
    private float lastY;
    private boolean pointerActive;
    private float dragStartValue;
    private boolean dragging;
    private boolean releaseWaiting;

    private boolean frameQueued;
    private long lastFrameNs;
    private long lastSampleMs;
    private float lastSampleValue;

    DropletDragController(View droplet, ViewGroup tabRow, float density, boolean night) {
        this.dropletRef = new WeakReference<>(droplet);
        this.rowRef = new WeakReference<>(tabRow);
        this.touchSlop = ViewConfiguration.get(droplet.getContext()).getScaledTouchSlop();
        this.density = density;
        this.positionSpring = new Spring(1f, 1000f, 0.001f, 0f);
        this.velocitySpring = new Spring(0.5f, 300f, 0.01f, 0f);
        this.pressSpring = new Spring(1f, 1000f, 0.001f, 0f);
        this.scaleXSpring = new Spring(0.6f, 250f, 0.001f, 1f);
        this.scaleYSpring = new Spring(0.7f, 250f, 0.001f, 1f);
        this.parallaxSpring = new Spring(0.5f, 300f, 0.01f, 0f);
    }

    void setPill(View restingPill) {
        pillRef = new WeakReference<>(restingPill);
    }

    /** Retained for the installer contract; growth targets the pill and row. */
    void setHost(View growthHost) {
        // no-op
    }

    /** Bind to a freshly laid-out tab row (the host may replace it at runtime). */
    void setTabRow(ViewGroup tabRow) {
        rowRef = new WeakReference<>(tabRow);
        dragging = false;
        releaseWaiting = false;
        lastSampleMs = 0L;
        lastFrameNs = 0L;
        float top = tabCountUnits(tabRow) - 1f;
        positionSpring.snapTo(clamp(positionSpring.value(), 0f, top));
        velocitySpring.snapTo(0f);
        pressSpring.snapTo(0f);
        scaleXSpring.snapTo(1f);
        scaleYSpring.snapTo(1f);
        parallaxSpring.snapTo(0f);
        render();
    }

    /** External selection change; ignored while the finger owns the droplet. */
    void animateToIndex(int index, boolean immediate) {
        if (dragging) {
            return;
        }
        ViewGroup row = rowRef.get();
        float target = clamp(index, 0f, Math.max(0f, tabCountUnits(row) - 1f));
        if (!immediate && Math.abs(positionSpring.target() - target) < 0.01f) {
            // Our own release already drove the spring here; the bounced selection
            // event must not animate it a second time.
            return;
        }
        if (immediate) {
            positionSpring.snapTo(target);
            velocitySpring.snapTo(0f);
            pressSpring.snapTo(0f);
            scaleXSpring.snapTo(1f);
            scaleYSpring.snapTo(1f);
            parallaxSpring.snapTo(0f);
            render();
            return;
        }
        beginPress();
        positionSpring.animateTo(target);
        velocitySpring.animateTo(0f);
        releaseWaiting = true;
        queueFrame();
    }

    @Override
    public boolean onIntercept(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = event.getX();
                downY = lastY = event.getY();
                dragging = false;
                if (isOverDroplet(event)) {
                    pointerActive = true;
                    beginPress();
                    queueFrame();
                }
                return false;
            case MotionEvent.ACTION_MOVE:
                lastX = event.getX();
                lastY = event.getY();
                if (dragging) {
                    return true;
                }
                View droplet = dropletRef.get();
                if (droplet == null || droplet.getVisibility() != View.VISIBLE) {
                    return false;
                }
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy)) {
                    dragging = true;
                    dragStartValue = positionSpring.target();
                    pointerActive = true;
                    beginPress();
                    queueFrame();
                    return true;
                }
                return false;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                pointerActive = false;
                finishPress();
                return false;
            default:
                return false;
        }
    }

    @Override
    public boolean onTouch(MotionEvent event) {
        ViewGroup row = rowRef.get();
        if (!dragging || row == null) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                pointerActive = false;
                finishPress();
            }
            return false;
        }
        float maxUnit = tabCountUnits(row) - 1f;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_MOVE: {
                lastX = event.getX();
                lastY = event.getY();
                float width = tabWidth(row);
                if (width > 0f) {
                    float unit = dragStartValue + (event.getX() - downX) / width;
                    positionSpring.animateTo(clamp(unit, 0f, maxUnit));
                    parallaxSpring.snapTo(event.getX() - downX);
                    queueFrame();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                int nearest = Math.round(clamp(positionSpring.target(), 0f, maxUnit));
                positionSpring.animateTo(nearest);
                velocitySpring.animateTo(0f);
                parallaxSpring.animateTo(0f);
                dragging = false;
                pointerActive = false;
                finishPress();
                View tab = TabBarBridge.tabAt(row, nearest);
                if (tab != null && !tab.isSelected()) {
                    tab.performClick();
                }
                return true;
            }
            default:
                return true;
        }
    }

    private boolean isOverDroplet(MotionEvent event) {
        View droplet = dropletRef.get();
        if (droplet == null || droplet.getVisibility() != View.VISIBLE) {
            return false;
        }
        float left = droplet.getLeft() + droplet.getTranslationX();
        ViewGroup.LayoutParams lp = droplet.getLayoutParams();
        float width = lp != null && lp.width > 0 ? lp.width : droplet.getWidth();
        float x = event.getX();
        return x >= left && x <= left + width;
    }

    private void beginPress() {
        lastSampleMs = 0L;
        pressSpring.animateTo(1f);
        scaleXSpring.animateTo(PRESSED_RATIO);
        scaleYSpring.animateTo(PRESSED_RATIO);
    }

    private void finishPress() {
        releaseWaiting = true;
        queueFrame();
    }

    /** Let the press scales relax only once the position has nearly settled. */
    private void completeReleaseIfSettled() {
        if (!releaseWaiting || dragging) {
            return;
        }
        float tolerance = Math.max((tabCountUnits(rowRef.get()) - 1f) * SETTLE_TOLERANCE, 0.001f);
        if (Math.abs(positionSpring.value() - positionSpring.target()) > tolerance) {
            return;
        }
        releaseWaiting = false;
        pressSpring.animateTo(0f);
        scaleXSpring.animateTo(1f);
        scaleYSpring.animateTo(1f);
    }

    private void queueFrame() {
        if (frameQueued) {
            return;
        }
        frameQueued = true;
        lastFrameNs = 0L;
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    private final Choreographer.FrameCallback frameCallback = this::onFrame;

    private void onFrame(long frameNs) {
        frameQueued = false;
        float dt = lastFrameNs == 0L ? 1f / 60f : (frameNs - lastFrameNs) / 1e9f;
        lastFrameNs = frameNs;

        boolean active = positionSpring.update(dt);
        sampleVelocity();
        active |= velocitySpring.update(dt);
        active |= pressSpring.update(dt);
        active |= scaleXSpring.update(dt);
        active |= scaleYSpring.update(dt);
        active |= parallaxSpring.update(dt);

        render();
        completeReleaseIfSettled();

        active |= positionSpring.isRunning() || velocitySpring.isRunning()
                || pressSpring.isRunning() || scaleXSpring.isRunning()
                || scaleYSpring.isRunning() || parallaxSpring.isRunning();
        if (active || releaseWaiting || dragging) {
            frameQueued = true;
            Choreographer.getInstance().postFrameCallback(frameCallback);
        }
    }

    /** Velocity over tab units per second, divided by the unit range. */
    private void sampleVelocity() {
        long now = SystemClock.uptimeMillis();
        if (lastSampleMs == 0L) {
            lastSampleMs = now;
            lastSampleValue = positionSpring.value();
            return;
        }
        float elapsed = now - lastSampleMs;
        if (elapsed < 8f) {
            return;
        }
        float perSecond = (positionSpring.value() - lastSampleValue) * 1000f / elapsed;
        float range = Math.max(1f, tabCountUnits(rowRef.get()) - 1f);
        velocitySpring.animateTo(perSecond / range);
        lastSampleMs = now;
        lastSampleValue = positionSpring.value();
    }

    private void render() {
        View droplet = dropletRef.get();
        ViewGroup row = rowRef.get();
        if (droplet == null || row == null || TabBarBridge.tabCount(row) == 0) {
            return;
        }
        float width = tabWidth(row);
        if (width <= 0f) {
            return;
        }
        View first = TabBarBridge.tabAt(row, 0);
        if (first == null) {
            return;
        }
        ViewGroup.LayoutParams lp = droplet.getLayoutParams();
        float dropletWidth = lp != null && lp.width > 0 ? lp.width : droplet.getWidth();
        float startX = row.getLeft() + first.getLeft() + (first.getWidth() - dropletWidth) * 0.5f;

        float parallax = parallaxOffset(row);
        if (Math.abs(parallax) < 0.5f) {
            parallax = 0f;
        }
        droplet.setTranslationX(startX + positionSpring.value() * width + parallax);

        float v = velocitySpring.value() / 10f;
        float along = clamp(v * 0.75f, -MAX_STRETCH, MAX_STRETCH);
        float across = clamp(v * 0.25f, -MAX_STRETCH, MAX_STRETCH);
        droplet.setScaleX(scaleXSpring.value() / (1f - along));
        droplet.setScaleY(scaleYSpring.value() * (1f - across));

        float progress = pressSpring.value();
        View pill = pillRef.get();
        float grow = 1f;
        if (pill != null && pill.getWidth() > 0) {
            grow = 1f + (HOST_GROWTH_DP * density / pill.getWidth()) * progress;
        }
        if (Math.abs(grow - 1f) < 0.002f) {
            grow = 1f;
        }
        if (droplet instanceof DropletPanel) {
            DropletPanel moving = (DropletPanel) droplet;
            moving.setProgress(progress);
            moving.setTouchPoint(lastX, lastY, pointerActive && progress > 0.01f);
            moving.setMotion(grow);
            // Progress can hold at 1 while the droplet is dragged; the
            // combined backdrop capture must follow the moving position.
            moving.refresh();
        }

        // Container and the real icon row grow and parallax together; the
        // droplet composites its own backdrop so it only follows translation.
        if (pill != null) {
            if (pill.getWidth() > 0) {
                pill.setScaleX(grow);
                pill.setScaleY(grow);
            }
            pill.setTranslationX(parallax);
            row.setPivotX(row.getWidth() * 0.5f);
            row.setPivotY(row.getHeight() * 0.5f);
            row.setScaleX(grow);
            row.setScaleY(grow);
            if (pill instanceof LiquidGlassPanel) {
                float centreX = droplet.getLeft() + droplet.getTranslationX()
                        + dropletWidth * 0.5f - pill.getLeft() - parallax;
                ((LiquidGlassPanel) pill).setInteraction(progress, centreX);
            }
        }
        row.setTranslationX(parallax);
        float tabScale = 1f + (TAB_PRESS_SCALE - 1f) * progress;
        if (Math.abs(tabScale - 1f) < 0.002f) {
            tabScale = 1f;
        }
        applyTabScales(row, tabScale);
    }

    /** Upstream eases the raw drag offset against the full bar width. */
    private float parallaxOffset(ViewGroup row) {
        View pill = pillRef.get();
        float reference = pill != null && pill.getWidth() > 0 ? pill.getWidth() : row.getWidth();
        if (reference <= 0f) {
            return 0f;
        }
        float fraction = clamp(parallaxSpring.value() / reference, -1f, 1f);
        float eased = 1f - (1f - Math.abs(fraction)) * (1f - Math.abs(fraction));
        return PARALLAX_DP * density * Math.signum(fraction) * eased;
    }

    /** All tab glyphs scale together while the bar is pressed (upstream 1.2x). */
    private void applyTabScales(ViewGroup row, float scale) {
        for (int i = 0; i < row.getChildCount(); i++) {
            View tab = row.getChildAt(i);
            tab.setPivotX(tab.getWidth() * 0.5f);
            tab.setPivotY(tab.getHeight() * 0.5f);
            tab.setScaleX(scale);
            tab.setScaleY(scale);
        }
    }

    private static float tabWidth(ViewGroup row) {
        if (row == null || TabBarBridge.tabCount(row) == 0) {
            return 0f;
        }
        View first = TabBarBridge.tabAt(row, 0);
        return first == null ? 0f : first.getWidth();
    }

    private static float tabCountUnits(ViewGroup row) {
        return Math.max(1, TabBarBridge.tabCount(row));
    }

    private static float clamp(float value, float low, float high) {
        return value < low ? low : Math.min(value, high);
    }
}
