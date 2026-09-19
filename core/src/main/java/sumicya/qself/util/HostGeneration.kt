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

        /**
         * Version names that start with these were still pre-NT, everything
         * from 9.0.0 on is NT. Used by the settings UI, which runs in the
         * module's own process and therefore cannot probe for QQ classes.
         */
        private val PRE_NT_PREFIXES = arrayOf("8.9.6", "8.9.7", "8.9.8", "8.9.9")

        /** Numbers of dex files that existed for the dumped 9.2.10 build, for the doc trail. */
        fun detect(resolve: (String) -> Class<*>?): HostGeneration =
            if (NT_MARKERS.any { resolve(it) != null }) NT else PRE_NT

        /**
         * Best-effort verdict from the installed host's version name alone.
         * QQ 9.x is NT; the 8.9.6-8.9.9 builds were its previews. Returns null
         * when the version cannot be read, in which case callers should not
         * claim anything.
         */
        fun fromVersion(versionName: String?): HostGeneration? {
            val version = versionName?.trim().orEmpty()
            if (version.isEmpty()) return null
            if (PRE_NT_PREFIXES.any { version.startsWith(it) }) return PRE_NT
            val major = version.substringBefore('.').toIntOrNull() ?: return null
            return if (major >= 9) NT else PRE_NT
        }

        /** True when [feature] was not written for [host]. */
        fun mismatches(feature: HostGeneration, host: HostGeneration?): Boolean =
            host != null && feature != ANY && feature != host
    }
}
