/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.glass;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

/**
 * Coordinate math for layers drawn inside an ancestor that is scaled.
 *
 * <p>The whole bar grows while a tab is pressed, so both glass layers sit
 * under a scale transform. Their shaders sample the screen in untransformed
 * coordinates; sampling with the usual on-screen location helpers would
 * magnify the backdrop. These routines accumulate plain layout offsets and
 * ancestor scales outside of the canvas matrix.
 */
final class ViewGeom {

    // Reused by the anchor walk; all callers run on the UI thread.
    private static final int[] ROOT_LOCATION = new int[2];

    private ViewGeom() {
    }

    /**
     * Fills {@code out} with the view's screen position after undoing every
     * ancestor scale/translation: walk to the top adding raw layout offsets,
     * then anchor on the root's real screen location.
     */
    static boolean unscaledScreenPos(View view, int[] out) {
        float offsetX = 0f;
        float offsetY = 0f;
        View node = view;
        View top = view;
        while (node.getParent() instanceof View) {
            View parent = (View) node.getParent();
            offsetX += node.getLeft() + node.getTranslationX() - parent.getScrollX();
            offsetY += node.getTop() + node.getTranslationY() - parent.getScrollY();
            node = parent;
            top = node;
        }
        top.getLocationOnScreen(ROOT_LOCATION);
        out[0] = Math.round(ROOT_LOCATION[0] + offsetX);
        out[1] = Math.round(ROOT_LOCATION[1] + offsetY);
        return true;
    }

    /**
     * Layout offset of {@code descendant} within {@code ancestor} as plain
     * left/translation sums (no scales): coordinates in the ancestor's
     * untransformed layout space. Returns false when not a descendant.
     */
    static boolean offsetWithin(View descendant, ViewGroup ancestor, int[] out) {
        float x = 0f;
        float y = 0f;
        View node = descendant;
        while (node != ancestor && node.getParent() instanceof View) {
            View parent = (View) node.getParent();
            x += node.getLeft() + node.getTranslationX() - parent.getScrollX();
            y += node.getTop() + node.getTranslationY() - parent.getScrollY();
            node = parent;
        }
        if (node != ancestor) {
            out[0] = 0;
            out[1] = 0;
            return false;
        }
        out[0] = Math.round(x);
        out[1] = Math.round(y);
        return true;
    }

    /** Product of this view's scale and every ancestor's. */
    static float cumulativeScale(View view) {
        float product = 1f;
        View node = view;
        while (node != null) {
            product *= Math.abs(node.getScaleX());
            ViewParent parent = node.getParent();
            node = parent instanceof View ? (View) parent : null;
        }
        return product < 0.01f ? 1f : product;
    }
}
