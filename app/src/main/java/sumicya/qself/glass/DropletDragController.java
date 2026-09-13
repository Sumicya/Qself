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
 * Press and horizontal-drag behaviour of the selected-tab droplet. Five
 * independent springs carry position (measured in tab units so the feel is
 * screen-independent), normalised drag velocity for the stretch, press
 * progress, and the two press scales. The gesture is not claimed until the
 * finger crosses the horizontal touch slop, so ordinary tab taps keep
 * reaching the host.
 */
final class DropletDragController implements LiquidGlassHostLayout.DragHandler {

    private static final float PRESSED_RATIO = 78f / 56f;
    private static final float MAX_STRETCH = 0.2f;
    private static final float SETTLE_TOLERANCE = 0.025f;
    // The lens never grows while pressed: scaling the pill would magnify and
    // redraw its contents (the old centred miniature ghosting).
    private static final float HOST_GROWTH_DP = 0f;

    private final WeakReference<View> dropletRef;
    private WeakReference<ViewGroup> rowRef;
    private WeakReference<View> pillRef = new WeakReference<>(null);
    private WeakReference<View> growthRef = new WeakReference<>(null);

    private final int touchSlop;
    private final float density;

    private final Spring positionSpring;
    private final Spring velocitySpring;
    private final Spring pressSpring;
    private final Spring scaleXSpring;
    private final Spring scaleYSpring;

    private float downX;
    private float downY;
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
    }

    void setPill(View restingPill) {
        pillRef = new WeakReference<>(restingPill);
    }

    /** Container that grows while the droplet is held; defaults to the pill. */
    void setHost(View growthHost) {
        growthRef = new WeakReference<>(growthHost);
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
                downX = event.getX();
                downY = event.getY();
                dragging = false;
                if (isOverDroplet(event)) {
                    beginPress();
                    queueFrame();
                }
                return false;
            case MotionEvent.ACTION_MOVE:
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
                    beginPress();
                    queueFrame();
                    return true;
                }
                return false;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
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
            return false;
        }
        float maxUnit = tabCountUnits(row) - 1f;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_MOVE: {
                float width = tabWidth(row);
                if (width > 0f) {
                    float unit = dragStartValue + (event.getX() - downX) / width;
                    positionSpring.animateTo(clamp(unit, 0f, maxUnit));
                    queueFrame();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                int nearest = Math.round(clamp(positionSpring.target(), 0f, maxUnit));
                positionSpring.animateTo(nearest);
                velocitySpring.animateTo(0f);
                dragging = false;
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

        render();
        completeReleaseIfSettled();

        active |= positionSpring.isRunning() || velocitySpring.isRunning()
                || pressSpring.isRunning() || scaleXSpring.isRunning() || scaleYSpring.isRunning();
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
        droplet.setTranslationX(startX + positionSpring.value() * width);

        float v = velocitySpring.value() / 10f;
        float along = clamp(v * 0.75f, -MAX_STRETCH, MAX_STRETCH);
        float across = clamp(v * 0.25f, -MAX_STRETCH, MAX_STRETCH);
        droplet.setScaleX(scaleXSpring.value() / (1f - along));
        droplet.setScaleY(scaleYSpring.value() * (1f - across));

        float progress = pressSpring.value();
        if (droplet instanceof DropletPanel) {
            DropletPanel moving = (DropletPanel) droplet;
            moving.setProgress(progress);
            // translation alone does not invalidate, so refresh the capture.
            moving.refresh();
        }

        View pill = pillRef.get();
        if (pill != null && pill.getWidth() > 0) {
            float grow = 1f + (HOST_GROWTH_DP * density / pill.getWidth()) * progress;
            View grown = growthRef.get();
            View target = grown != null ? grown : pill;
            target.setScaleX(grow);
            target.setScaleY(grow);
            if (target != pill && pill.getScaleX() != 1f) {
                pill.setScaleX(1f);
                pill.setScaleY(1f);
            }
            if (pill instanceof LiquidGlassPanel) {
                float centreX = droplet.getLeft() + droplet.getTranslationX()
                        + dropletWidth * 0.5f - pill.getLeft();
                ((LiquidGlassPanel) pill).setInteraction(progress, centreX);
            }
        }
        resetTabScales(row);
    }

    /** Real tabs are never scaled; the enlarged glyph is the droplet's own copy. */
    private void resetTabScales(ViewGroup row) {
        for (int i = 0; i < row.getChildCount(); i++) {
            View tab = row.getChildAt(i);
            if (tab.getScaleX() != 1f) {
                tab.setScaleX(1f);
                tab.setScaleY(1f);
            }
        }
    }

    private static float tabWidth(ViewGroup row) {
        if (row == null || TabBarBridge.tabCount(row) == 0) {
            return 0f;
        }
        View first = TabBarBridge.tabAt(row, 0);
        return first == null ? 0f : first.getWidth();
    }

    private static int tabCountUnits(ViewGroup row) {
        return Math.max(1, TabBarBridge.tabCount(row));
    }

    private static float clamp(float value, float low, float high) {
        return value < low ? low : Math.min(value, high);
    }
}
