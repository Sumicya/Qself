/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.ui

import android.animation.ValueAnimator
import android.content.Context
import android.view.View
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import com.google.android.material.motion.MotionUtils

object SettingsMotion {
    fun enabled() = ValueAnimator.areAnimatorsEnabled()
    fun easing(context: Context): Interpolator = MotionUtils.resolveThemeInterpolator(context,
        com.google.android.material.R.attr.motionEasingEmphasizedInterpolator, PathInterpolator(.2f, 0f, 0f, 1f))
    fun duration(context: Context, short: Boolean = false): Long = MotionUtils.resolveThemeDuration(context,
        if (short) com.google.android.material.R.attr.motionDurationShort4 else com.google.android.material.R.attr.motionDurationMedium2,
        if (short) 200 else 300).toLong()
    fun enter(view: View) {
        view.animate().cancel()
        if (!enabled()) { view.alpha = 1f; view.translationY = 0f; return }
        view.alpha = 0f; view.translationY = SettingsVisuals.dp(view.context, 32).toFloat()
        view.animate().alpha(1f).translationY(0f).setDuration(duration(view.context))
            .setInterpolator(easing(view.context)).start()
    }
}
