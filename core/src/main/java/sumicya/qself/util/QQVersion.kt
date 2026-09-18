/*
 * Qself — a free, modern Xposed module for QQ.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package sumicya.qself.util

/**
 * QQ long-version-code thresholds, ported from the upstream table so the
 * version branching in the ported features keeps working unchanged.
 */
object QQVersion {

    const val QQ_8_6_0 = 1672L
    const val QQ_8_8_11 = 1898L
    const val QQ_8_8_17 = 1938L
    const val QQ_8_8_93 = 2886L
    const val QQ_8_9_3 = 3118L
    const val QQ_8_9_5 = 3176L
    const val QQ_8_9_10 = 3292L
    const val QQ_8_9_25 = 3640L
    const val QQ_8_9_28 = 3698L
    const val QQ_8_9_63_BETA_11345 = 4176L
    const val QQ_8_9_68 = 4264L
    const val QQ_8_9_70 = 4330L
    const val QQ_8_9_88 = 4852L
    const val QQ_8_9_90 = 4938L
    const val QQ_9_0_0 = 5282L
    const val QQ_9_0_8 = 5540L
    const val QQ_9_0_20 = 5844L
    const val QQ_9_0_25 = 5942L
    const val QQ_9_0_30 = 6038L
    const val QQ_9_0_35 = 6150L
    const val QQ_9_0_85 = 7068L
    const val QQ_9_0_90 = 7218L
    const val QQ_9_1_30 = 8538L
    const val QQ_9_1_50 = 9048L
    const val QQ_9_1_70 = 9898L

    fun isAtLeast(versionCode: Long, threshold: Long): Boolean = versionCode >= threshold
}
