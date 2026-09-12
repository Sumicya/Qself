/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.adapter.ui

import android.view.View
import android.widget.ImageView
import io.github.qauxv.util.Initiator
import io.github.qauxv.util.PlayQQVersion
import io.github.qauxv.util.QQVersion
import io.github.qauxv.util.requireRangePlayQQVersion
import io.github.qauxv.util.xpcompat.XC_MethodHook
import io.github.qauxv.util.xpcompat.XposedBridge
import sumicya.qself.hostapi.ui.ConversationTitleBarApi
import sumicya.qself.hostapi.ui.ConversationTitleBarApi.CameraHandle
import sumicya.qself.hostapi.ui.ConversationTitleBarApi.SuperShowHandle
import top.linl.util.reflect.FieldUtils
import java.lang.reflect.Method

/**
 * Adapter for the conversation title bar (RFC-03 §8 batch-2).
 *
 * The version→obfuscated-name mapping tables are exposed as **pure
 * functions of the version code** — the third testable seam: their
 * boundary continuity is fully verifiable on the JVM without any host
 * state. Device-side resolution reads the real version once and feeds
 * the same tables.
 */
object ConversationTitleBarAdapter : ConversationTitleBarApi {

    /** Host generations of the Super QQShow badge, by version code. */
    enum class SuperShowGeneration {
        /** &gt;= 9.0.20: ConversationTitleBtnConfig validator (return-false blank). */
        CONFIG_VALIDATOR,

        /** &gt;= 8.9.10: ZPlanBadgeManagerImpl.onCreateView(View, boolean). */
        BADGE_TWO_ARGS,

        /** &gt;= 8.9.3: ZPlanBadgeManagerImpl.onCreateView(View). */
        BADGE_ONE_ARG,

        /** older: ConversationTitleBtnCtrl.b(D)(View). */
        LEGACY_CTRL,
    }

    /** version table (hide entry): static-ish void x(View) by version. */
    fun cameraHideName(versionCode: Long): String = when {
        versionCode >= QQVersion.QQ_8_9_63_BETA_11345 -> "D"
        versionCode >= QQVersion.QQ_8_9_10 -> "C"
        versionCode >= QQVersion.QQ_8_8_93 -> "G"
        else -> "a"
    }

    /** version table (remove entry): void x() by version. */
    fun cameraRemoveName(versionCode: Long): String = when {
        versionCode >= QQVersion.QQ_8_9_63_BETA_11345 -> "C"
        versionCode >= QQVersion.QQ_8_9_10 -> "B"
        versionCode >= QQVersion.QQ_8_9_5 -> "E"
        versionCode >= QQVersion.QQ_8_8_93 -> "F"
        else -> "a"
    }

    fun superShowGeneration(versionCode: Long): SuperShowGeneration = when {
        versionCode >= QQVersion.QQ_9_0_20 -> SuperShowGeneration.CONFIG_VALIDATOR
        versionCode >= QQVersion.QQ_8_9_10 -> SuperShowGeneration.BADGE_TWO_ARGS
        versionCode >= QQVersion.QQ_8_9_3 -> SuperShowGeneration.BADGE_ONE_ARG
        else -> SuperShowGeneration.LEGACY_CTRL
    }

    override fun resolveCameraButton(classLoader: ClassLoader): CameraHandle? = runCatching {
        if (requireRangePlayQQVersion(PlayQQVersion.PlayQQ_8_2_11, PlayQQVersion.PlayQQ_8_2_11)) {
            CameraHandle.PlayQqCrop(classLoader.loadClass("aawg").getDeclaredMethod("a"))
        } else {
            val ctrl = Initiator._ConversationTitleBtnCtrl() ?: return null
            val version = io.github.qauxv.util.hostInfo.versionCode
            val hide = ctrl.declaredMethods.singleOrNull {
                it.name == cameraHideName(version) && it.returnType == Void.TYPE
                    && it.parameterTypes.contentEquals(arrayOf(View::class.java))
            } ?: return null
            val remove = ctrl.declaredMethods.singleOrNull {
                it.name == cameraRemoveName(version) && it.returnType == Void.TYPE
                    && it.parameterTypes.isEmpty()
            } ?: return null
            CameraHandle.QqPath(hide, remove)
        }
    }.getOrNull()

    override fun resolveSuperShowBadge(classLoader: ClassLoader): SuperShowHandle? = runCatching {
        val version = io.github.qauxv.util.hostInfo.versionCode
        when (superShowGeneration(version)) {
            SuperShowGeneration.CONFIG_VALIDATOR -> {
                val clz = classLoader.loadClass(
                    "com.tencent.mobileqq.util.conversationtitlebutton.a")
                SuperShowHandle.ConfigValidator(clz.declaredMethods.single {
                    it.parameterTypes.isEmpty() && it.returnType == java.lang.Boolean.TYPE
                })
            }
            SuperShowGeneration.BADGE_TWO_ARGS -> {
                SuperShowHandle.BadgeView(Initiator._ZPlanBadgeManagerImpl()!!.declaredMethods
                    .single {
                        it.name == "onCreateView" && it.returnType == Void.TYPE
                            && it.parameterTypes.contentEquals(
                                arrayOf(View::class.java, java.lang.Boolean.TYPE))
                    })
            }
            SuperShowGeneration.BADGE_ONE_ARG -> {
                SuperShowHandle.BadgeView(Initiator._ZPlanBadgeManagerImpl()!!.declaredMethods
                    .single {
                        it.name == "onCreateView" && it.returnType == Void.TYPE
                            && it.parameterTypes.contentEquals(arrayOf(View::class.java))
                    })
            }
            SuperShowGeneration.LEGACY_CTRL -> {
                SuperShowHandle.BadgeView(Initiator._ConversationTitleBtnCtrl()!!.declaredMethods
                    .single {
                        (it.name == "b" || it.name == "D") && it.returnType == Void.TYPE
                            && it.parameterTypes.contentEquals(arrayOf(View::class.java))
                    })
            }
        }
    }.getOrNull()

    override fun installCameraRemove(
        handle: CameraHandle,
        isEnabled: () -> Boolean,
        onError: (Throwable) -> Unit,
    ): Boolean = runCatching {
        when (handle) {
            is CameraHandle.QqPath -> {
                suppress(handle.hideMethod, isEnabled, onError)
                suppress(handle.removeMethod, isEnabled, onError)
            }
            is CameraHandle.PlayQqCrop -> {
                XposedBridge.hookMethod(handle.cropMethod, object : XC_MethodHook(50) {
                    override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        try {
                            if (isEnabled()) {
                                val view = FieldUtils.getField(
                                    param.thisObject, "a", ImageView::class.java) as ImageView
                                view.visibility = View.GONE
                                FieldUtils.setField(
                                    param.thisObject, "a", ImageView::class.java, view)
                            }
                        } catch (e: Throwable) {
                            onError(e)
                        }
                    }
                })
            }
        }
        true
    }.getOrElse {
        onError(it)
        false
    }

    override fun installSuperShowRemove(
        handle: SuperShowHandle,
        isEnabled: () -> Boolean,
        onError: (Throwable) -> Unit,
    ): Boolean = runCatching {
        when (handle) {
            // legacy fidelity: the validator path was not runtime-gated
            is SuperShowHandle.ConfigValidator -> {
                XposedBridge.hookMethod(handle.method, object : XC_MethodHook(50) {
                    override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        try {
                            param.result = java.lang.Boolean.FALSE
                        } catch (e: Throwable) {
                            onError(e)
                        }
                    }
                })
            }
            is SuperShowHandle.BadgeView -> suppress(handle.method, isEnabled, onError)
        }
        true
    }.getOrElse {
        onError(it)
        false
    }

    private fun suppress(
        method: Method,
        isEnabled: () -> Boolean,
        onError: (Throwable) -> Unit,
    ) {
        XposedBridge.hookMethod(method, object : XC_MethodHook(50) {
            override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                try {
                    if (isEnabled()) {
                        param.result = null
                    }
                } catch (e: Throwable) {
                    onError(e)
                }
            }
        })
    }
}
