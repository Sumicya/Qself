/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import com.google.android.material.motion.MotionUtils

/**
 * The single expansion engine of the settings UI.
 *
 * Every level — a category card body and a row panel — grows through
 * [expandBody] and shrinks through [shrinkBody], so one gesture always produces
 * one motion (same interpolator, same duration, same height animation). Before
 * this was the only engine, a card and a panel animated differently for the
 * same tap.
 *
 * Both methods return the running animator, or null when the view is not ready
 * to animate (then the caller applies its static end state). Callers cancel the
 * returned animator in place; the end callbacks are written to stay correct
 * even when cancelled mid-flight.
 */
object SettingsMotion {

    fun enabled(): Boolean = ValueAnimator.areAnimatorsEnabled()

    fun easing(context: Context): TimeInterpolator = MotionUtils.resolveThemeInterpolator(
        context,
        com.google.android.material.R.attr.motionEasingEmphasizedInterpolator,
        PathInterpolator(.2f, 0f, 0f, 1f))

    fun duration(context: Context, short: Boolean = false): Long = MotionUtils.resolveThemeDuration(
        context,
        if (short) com.google.android.material.R.attr.motionDurationShort4
        else com.google.android.material.R.attr.motionDurationMedium2,
        if (short) 200 else 300).toLong()

    /**
     * Grows [view] to its measured height, ending on WRAP_CONTENT.
     *
     * Starts from the view's current height instead of zero, so a rapid
     * re-expansion mid-shrink continues from where the shrink got to rather
     * than jumping.
     */
    fun expandBody(view: View): ValueAnimator? {
        view.visibility = View.VISIBLE
        if (!enabled()) return null
        val parent = view.parent as? ViewGroup ?: return null
        val available = parent.width - parent.paddingStart - parent.paddingEnd
        if (available <= 0) return null
        view.measure(
            View.MeasureSpec.makeMeasureSpec(available, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val target = view.measuredHeight
        val params = view.layoutParams ?: return null
        if (target <= 0) return null
        val start = view.height.coerceIn(0, target)
        if (start == target) {
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT
            view.layoutParams = params
            return null
        }
        params.height = start
        view.layoutParams = params
        return ValueAnimator.ofInt(start, target).apply {
            duration = duration(view.context)
            interpolator = easing(view.context)
            addUpdateListener {
                params.height = it.animatedValue as Int
                view.layoutParams = params
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    view.layoutParams = params
                }
            })
            start()
        }
    }

    /**
     * Shrinks [view] to zero height; [onEnd] always runs, exactly once, from
     * whichever of cancel/end arrives first.
     */
    fun shrinkBody(view: View, onEnd: () -> Unit): ValueAnimator? {
        val params = view.layoutParams
        if (!enabled() || !view.isAttachedToWindow || view.height <= 0 || params == null) {
            onEnd()
            return null
        }
        return ValueAnimator.ofInt(view.height, 0).apply {
            duration = duration(view.context, true)
            interpolator = easing(view.context)
            addUpdateListener {
                params.height = it.animatedValue as Int
                view.layoutParams = params
            }
            addListener(object : AnimatorListenerAdapter() {
                private var done = false

                private fun finish() {
                    if (!done) {
                        done = true
                        onEnd()
                    }
                }

                override fun onAnimationCancel(animation: Animator) = finish()
                override fun onAnimationEnd(animation: Animator) = finish()
            })
            start()
        }
    }

    /** Shrinks a panel and detaches it — the panel's own route out of the tree. */
    fun collapse(view: View, onEnd: () -> Unit) {
        shrinkBody(view) {
            (view.parent as? ViewGroup)?.removeView(view)
            onEnd()
        }
    }
}
