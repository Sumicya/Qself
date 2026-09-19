/*
 * Qself — a free, modern Xposed module for QQ.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package sumicya.qself.util

/**
 * Which generation of QQ a feature is written for.
 *
 * QQ 9.x moved to the *NT* architecture (`com.tencent.qqnt.*`): nearly every
 * class the pre-NT features target was renamed or replaced, so those features
 * cannot work on it — they used to fail one by one with
 * `ClassNotFoundException`, which made the log look broken when it was simply
 * "wrong generation" (see docs/NT-ADAPTATION.md).
 *
 * Declaring the generation makes that explicit: [sumicya.qself.Qself] skips a
 * feature whose generation does not match the host, with a single INFO line.
 */
enum class HostGeneration(val title: String) {
    /** QQ 8.x / TIM 3.x — everything before the NT rewrite. */
    PRE_NT("旧版 QQ"),

    /** QQ 9.x (NT) — `com.tencent.qqnt.*`. */
    NT("NT QQ"),

    /** Works on both (usually because it targets a stable, shared class). */
    ANY("通用");

    companion object {
        /**
         * Class names that only exist in the NT architecture. Checked through
         * the host's [Host] resolver, so hit and miss are both cached.
         */
        private val NT_MARKERS = arrayOf(
            "com.tencent.qqnt.base.BaseActivity",
            "com.tencent.qqnt.startup.NtStartup",
            "com.tencent.qqnt.kernel.api.IEmoticonService",
        )

        /** Numbers of dex files that existed for the dumped 9.2.10 build, for the doc trail. */
        fun detect(resolve: (String) -> Class<*>?): HostGeneration =
            if (NT_MARKERS.any { resolve(it) != null }) NT else PRE_NT
    }
}
