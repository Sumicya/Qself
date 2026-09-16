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
 * Every level - a category card body and a row panel - grows and shrinks
 * through [expandBody] / [shrinkBody]. Exactly one motion exists: same
 * interpolator, same duration, same height animation. Before this was
 * centralised the card used its own ValueAnimator while panels added a second
 * fade/slide path, so the same gesture produced two different motions
 * ("动画种类问题").
 */
object SettingsMotion {
    fun enabled() = ValueAnimator.areAnimatorsEnabled()

    fun easing(context: Context): TimeInterpolator = MotionUtils.resolveThemeInterpolator(
        context, com.google.android.material.R.attr.motionEasingEmphasizedInterpolator, PathInterpolator(.2f, 0f, 0f, 1f))

    fun duration(context: Context, short: Boolean = false): Long = MotionUtils.resolveThemeDuration(
        context,
        if (short) com.google.android.material.R.attr.motionDurationShort4 else com.google.android.material.R.attr.motionDurationMedium2,
        if (short) 200 else 300).toLong()

    /**
     * Grow [view] from zero to its measured height, ending on WRAP_CONTENT.
     *
     * Returns the running animator so a caller can cancel it, or null when the
     * view is not ready to animate (then it is simply shown).
     */
    fun expandBody(view: View): ValueAnimator? {
        view.visibility = View.VISIBLE
        if (!enabled()) return null
        val parent = view.parent as? ViewGroup ?: return null
        val available = parent.width - parent.paddingStart - parent.paddingEnd
        if (available <= 0) return null
        view.measure(View.MeasureSpec.makeMeasureSpec(available, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val target = view.measuredHeight
        val params = view.layoutParams ?: return null
        if (target <= 0) return null
        params.height = 0
        view.layoutParams = params
        return ValueAnimator.ofInt(0, target).apply {
            duration = duration(view.context)
            interpolator = easing(view.context)
            addUpdateListener { params.height = it.animatedValue as Int; view.layoutParams = params }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    view.layoutParams = params
                }
            })
            start()
        }
    }

    /** Shrink [view] to zero height; [onEnd] always runs, exactly once. */
    fun shrinkBody(view: View, onEnd: () -> Unit) {
        val params = view.layoutParams
        if (!enabled() || !view.isAttachedToWindow || view.height <= 0 || params == null) { onEnd(); return }
        ValueAnimator.ofInt(view.height, 0).apply {
            duration = duration(view.context, true)
            interpolator = easing(view.context)
            addUpdateListener { params.height = it.animatedValue as Int; view.layoutParams = params }
            addListener(object : AnimatorListenerAdapter() {
                private var done = false
                private fun finish() { if (!done) { done = true; onEnd() } }
                override fun onAnimationCancel(animation: Animator) = finish()
                override fun onAnimationEnd(animation: Animator) = finish()
            })
            start()
        }
    }

    /** Shrink a panel and detach it - the panel's own route out of the tree. */
    fun collapse(view: View, onEnd: () -> Unit) {
        shrinkBody(view) {
            (view.parent as? ViewGroup)?.removeView(view)
            onEnd()
        }
    }
}
