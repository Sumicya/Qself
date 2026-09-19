/*
 * Qself — a free, modern Xposed module for QQ.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package sumicya.qself.host

import android.content.Context
import java.io.File
import java.util.zip.ZipFile
import sumicya.qself.dex.DexClass
import sumicya.qself.dex.DexReader
import sumicya.qself.log.QLog

/**
 * Runtime class discovery in the host's own APK — the Qself equivalent of the
 * DexKit step every upstream module relies on.
 *
 * Hardcoded FQCN candidates stop working the moment QQ renames or obfuscates a
 * class (`com.tencent.mobileqq.setting.main.b` is exactly that: the settings
 * provider of 9.2.30+). Upstream solves it by *searching the dex* for the
 * class instead of trusting a name. This does the same, with what Qself
 * already has: [DexReader] for the format and the host APK on disk.
 *
 * Discovery is by **behaviour**, never by name alone:
 *
 *  - [find] filters class descriptors by fragments (`setting/main`), decodes
 *    their real method signatures from the dex, and hands each one to the
 *    caller's predicate;
 *  - the caller's predicate is what decides — e.g. "has a `Collection (Context)`
 *    method" for a settings provider — so a rename cannot break it;
 *  - the verdict is **cached** in the host's own files dir, so the scan cost is
 *    paid once per QQ version instead of at every boot (`hostdex.txt`).
 *
 * In the host process the APK is readable directly (it is the app's own
 * `sourceDir`), so no root and no `su` are involved — unlike the UI-side
 * `ClassDump`, which has to `su cp` the APK out. Scanning is expensive
 * (hundreds of MB of dex), so [find] is expected to be called off the boot
 * thread and only when the cheap candidates missed.
 */
object HostDex {

    private const val TAG = "HostDex"

    /** Cached decisions, key `purpose`, value `Lfoo/Bar;` descriptor. */
    private const val CACHE_FILE = "qself/hostdex.txt"

    /** Largest dex that will be read into memory in one piece. */
    private const val MAX_DEX_BYTES = 80L * 1024 * 1024

    /** Upper bound on decoded classes handed to a predicate, as a safety net. */
    private const val MAX_CANDIDATES = 4000

    private val cache = LinkedHashMap<String, String>()
    private var cacheLoaded = false
    private val lock = Any()

    /** `Lcom/tencent/mobileqq/setting/main/b;` -> `com.tencent.mobileqq.setting.main.b`. */
    fun toFqcn(descriptor: String): String =
        descriptor.removePrefix("L").removeSuffix(";").replace('/', '.')

    /**
     * Descriptor remembered for [purpose] (`settings.provider`), or null.
     * Cached names are still validated by the caller — a stale name from a
     * previous QQ version must not turn into a wrong hook.
     */
    fun recall(context: Context, purpose: String): String? {
        loadCache(context)
        return synchronized(lock) { cache[purpose] }
    }

    /** Remember [descriptor] for [purpose] so the next boot skips the scan. */
    fun remember(context: Context, purpose: String, descriptor: String) {
        loadCache(context)
        synchronized(lock) {
            if (cache[purpose] == descriptor) return
            cache[purpose] = descriptor
            writeCache(context)
        }
    }

    /**
     * Every class in the host APK whose descriptor contains one of [fragments]
     * **and** satisfies [validate]. Blocking and I/O heavy: call it from a
     * background thread. Returns an empty list when the APK cannot be read —
     * callers then keep whatever candidates they had.
     */
    fun find(
        context: Context,
        fragments: List<String>,
        validate: (DexClass) -> Boolean,
    ): List<DexClass> {
        val apk = File(context.applicationInfo?.sourceDir ?: return emptyList())
        if (!apk.isFile || !apk.canRead()) {
            QLog.w(TAG, "host APK not readable: ${apk.absolutePath}")
            return emptyList()
        }
        val startedAt = System.currentTimeMillis()
        val matches = ArrayList<DexClass>()
        var scanned = 0
        try {
            ZipFile(apk).use { zip ->
                // QQ ships its dex as classes.dex, classes2.dex, ... — the same
                // convention every app uses; anything else is not code.
                val entries = zip.entries().toList()
                    .filter { entry ->
                        val name = entry.name
                        !entry.isDirectory &&
                            name.startsWith("classes") &&
                            name.endsWith(".dex") &&
                            entry.size in 1..MAX_DEX_BYTES
                    }
                for (entry in entries) {
                    val bytes = try {
                        zip.getInputStream(entry).use { it.readBytes() }
                    } catch (t: Throwable) {
                        QLog.w(TAG, "cannot read ${entry.name}: ${t.javaClass.simpleName}")
                        continue
                    }
                    val reader = DexReader(bytes)
                    if (!reader.valid) {
                        QLog.w(TAG, "${entry.name}: not a dex (endian/magic)")
                        continue
                    }
                    val decoded = reader.classDetails { descriptor ->
                        scanned++
                        scanned <= MAX_CANDIDATES && fragments.any { descriptor.contains(it) }
                    }
                    for (cls in decoded) {
                        if (validate(cls)) {
                            matches += cls
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            QLog.w(TAG, "discovery failed", t)
            return emptyList()
        }
        QLog.i(
            TAG,
            "scanned $scanned classes in ${System.currentTimeMillis() - startedAt} ms, " +
                "${matches.size} match(es) for ${fragments.joinToString("|")}",
        )
        return matches
    }

    /**
     * Descriptor of the first class matching the shape, remembering it for
     * [purpose] so later boots can try it first.
     */
    fun discover(context: Context, purpose: String, fragments: List<String>, validate: (DexClass) -> Boolean): String? {
        val hit = find(context, fragments, validate).firstOrNull() ?: return null
        QLog.i(TAG, "$purpose -> ${hit.descriptor}")
        remember(context, purpose, hit.descriptor)
        return hit.descriptor
    }

    private fun loadCache(context: Context) {
        synchronized(lock) {
            if (cacheLoaded) return
            cacheLoaded = true
            val file = File(context.filesDir, CACHE_FILE)
            if (!file.isFile) return
            try {
                file.readLines().forEach { line ->
                    val at = line.indexOf('=')
                    if (at > 0) {
                        cache[line.substring(0, at)] = line.substring(at + 1)
                    }
                }
                QLog.d(TAG, "cache loaded: ${cache.size} entry/entries")
            } catch (t: Throwable) {
                QLog.w(TAG, "cannot read the cache", t)
            }
        }
    }

    private fun writeCache(context: Context) {
        val file = File(context.filesDir, CACHE_FILE)
        try {
            file.parentFile?.mkdirs()
            val text = buildString {
                cache.forEach { (key, value) -> append(key).append('=').append(value).append('\n') }
            }
            file.writeText(text)
        } catch (t: Throwable) {
            QLog.w(TAG, "cannot write the cache", t)
        }
    }
}
