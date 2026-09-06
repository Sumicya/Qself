/*
 * QAuxiliary - An Xposed module for QQ/TIM
 * Copyright (C) 2019-2022 qwq233@qwq2333.top
 * https://github.com/cinit/QAuxiliary
 *
 * This software is non-free but opensource software: you can redistribute it
 * and/or modify it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation; either
 * version 3 of the License, or any later version and our eula as published
 * by QAuxiliary contributors.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * and eula along with this software.  If not, see
 * <https://www.gnu.org/licenses/>
 * <https://github.com/cinit/QAuxiliary/blob/master/LICENSE.md>.
 */
package sumicya.qself.adapter.ui

import android.content.res.AssetManager
import android.graphics.drawable.Drawable
import io.github.qauxv.ui.ResUtils
import io.github.qauxv.util.Initiator
import io.github.qauxv.util.LicenseStatus
import io.github.qauxv.util.Log
import io.github.qauxv.util.xpcompat.XC_MethodHook
import io.github.qauxv.util.xpcompat.XposedBridge
import sumicya.qself.hostapi.ui.SplashOverrideApi
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Batch-7 adapter: owns the host asset names, the framework AssetManager
 * interception, the optional SplashWidget / ThemeSplashHelper sites, and
 * the transparent 1x1 RGBA PNG used to blank out logos. The asset
 * classification and the synthetic-accessor trait are pure functions and
 * pinned on the JVM.
 */
object SplashOverrideAdapter : SplashOverrideApi {

    private const val TAG = "SplashOverrideAdapter"

    /** Host splash assets that may resolve to the user's custom image. */
    private val SPLASH_ASSETS = setOf(
        "splash.jpg", "splash.png", "splash_big.jpg",
        "splash/splash_simple.png", "splash/splash_big_simple.png", "splash/splash_main.png",
    )

    /** Host logo assets, blanked out with a transparent PNG. */
    private val LOGO_ASSETS = setOf(
        "splash_logo.png", "splash/splash_logo.png", "splash/splash_logo_night.png",
    )

    /** A well-formed 1x1 RGBA PNG (see SplashOverrideAdapterTest for the pinned layout). */
    @JvmField
    val TRANSPARENT_PNG: ByteArray = byteArrayOf(
        0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(), 0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x0D.toByte(), 0x49.toByte(), 0x48.toByte(), 0x44.toByte(), 0x52.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte(),
        0x08.toByte(), 0x06.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x1F.toByte(), 0x15.toByte(), 0xC4.toByte(),
        0x89.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x0B.toByte(), 0x49.toByte(), 0x44.toByte(), 0x41.toByte(),
        0x54.toByte(), 0x08.toByte(), 0xD7.toByte(), 0x63.toByte(), 0x60.toByte(), 0x00.toByte(), 0x02.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x05.toByte(), 0x00.toByte(), 0x01.toByte(), 0xE2.toByte(), 0x26.toByte(), 0x05.toByte(), 0x9B.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x49.toByte(), 0x45.toByte(), 0x4E.toByte(), 0x44.toByte(),
        0xAE.toByte(), 0x42.toByte(), 0x60.toByte(), 0x82.toByte(),
    )

    /** How a host asset name is to be treated by the AssetManager site. */
    enum class AssetKind {
        /** Splash image: may be replaced by the caller's stream. */
        SPLASH,
        /** Logo image: always blanked with the transparent PNG. */
        LOGO,
    }

    /** Pure classification of a host asset name; null = not our business. */
    fun classifyAsset(name: String): AssetKind? = when {
        name in SPLASH_ASSETS -> AssetKind.SPLASH
        name in LOGO_ASSETS -> AssetKind.LOGO
        else -> null
    }

    /** Shape half of the ThemeSplashHelper trait: `Map (int)`. */
    fun hasMapAccessorShape(m: Method): Boolean =
        m.returnType == Map::class.java
            && m.parameterTypes.size == 1
            && m.parameterTypes[0] == Int::class.javaPrimitiveType

    /**
     * Full trait, exactly as the original walk: shape + modifiers equal to
     * STATIC | SYNTHETIC (0x1000) — an exact comparison, not a bitmask test.
     */
    fun matchesSyntheticMapAccessor(m: Method): Boolean =
        hasMapAccessorShape(m) && m.modifiers == (Modifier.STATIC or 0x00001000)

    /**
     * Walks declared methods for the synthetic accessor; ambiguous matches
     * throw, message faithful to the original.
     */
    fun findSyntheticMapAccessor(clazz: Class<*>): Method? {
        var found: Method? = null
        for (m in clazz.declaredMethods) {
            if (matchesSyntheticMapAccessor(m)) {
                if (found != null) {
                    throw IllegalStateException("Too many ThemeSplashHelper.<synthetic>getSplashConfigMapByCId(I)Map")
                }
                found = m
            }
        }
        return found
    }

    override fun installSplashOverride(
        classLoader: ClassLoader,
        resolveOverride: (assetName: String, isDark: Boolean) -> InputStream?,
        isEnabled: () -> Boolean,
        onError: (Throwable) -> Unit,
    ): Boolean {
        // site 1 (mandatory): framework AssetManager.open(String, int), priority 53
        val open = AssetManager::class.java.getDeclaredMethod(
            "open", String::class.java, Int::class.javaPrimitiveType,
        )
        XposedBridge.hookMethod(open, object : XC_MethodHook(53) {
            override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                try {
                    if (!isEnabled() || LicenseStatus.sDisableCommonHooks) {
                        return
                    }
                    val fileName = param.args[0] as String
                    when (classifyAsset(fileName)) {
                        AssetKind.SPLASH -> {
                            val replacement = resolveOverride(fileName, ResUtils.isInNightMode())
                            if (replacement != null) {
                                param.result = replacement
                            }
                        }
                        AssetKind.LOGO -> param.result = ByteArrayInputStream(TRANSPARENT_PNG)
                        null -> {}
                    }
                } catch (e: Throwable) {
                    onError(e)
                    throw e
                }
            }
        })
        // site 2 (optional): SplashWidget.setSplashDrawable(Drawable, boolean) -> force light, priority 52
        val kSplashWidget = Initiator.load("com.tencent.mobileqq.splashad.SplashWidget")
        if (kSplashWidget == null) {
            Log.d("$TAG: SplashWidget absent, skip")
        } else {
            val setSplashDrawable = kSplashWidget.getDeclaredMethod(
                "setSplashDrawable", Drawable::class.java, java.lang.Boolean.TYPE,
            )
            XposedBridge.hookMethod(setSplashDrawable, object : XC_MethodHook(52) {
                override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                    try {
                        if (!isEnabled() || LicenseStatus.sDisableCommonHooks) {
                            return
                        }
                        param.args[1] = false
                    } catch (e: Throwable) {
                        onError(e)
                        throw e
                    }
                }
            })
        }
        // site 3 (optional): ThemeSplashHelper synthetic Map(int) -> null, priority 51
        val kThemeSplashHelper = Initiator.load("com.tencent.mobileqq.splashad.config.ThemeSplashHelper")
        if (kThemeSplashHelper == null) {
            Log.d("$TAG: ThemeSplashHelper absent, skip")
        } else {
            val accessor = findSyntheticMapAccessor(kThemeSplashHelper)
            if (accessor == null) {
                Log.d("$TAG: synthetic config accessor not found, skip")
            } else {
                XposedBridge.hookMethod(accessor, object : XC_MethodHook(51) {
                    override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        try {
                            if (!isEnabled() || LicenseStatus.sDisableCommonHooks) {
                                return
                            }
                            param.result = null
                        } catch (e: Throwable) {
                            onError(e)
                            throw e
                        }
                    }
                })
            }
        }
        return true
    }
}
