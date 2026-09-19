/*
 * Qself — a free, modern Xposed module for QQ.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package sumicya.qself.feature.misc

import android.content.Intent
import sumicya.qself.ProcessKind
import sumicya.qself.annotation.QselfFeature
import sumicya.qself.feature.FeatureCategory
import sumicya.qself.feature.FeatureContext
import sumicya.qself.feature.SwitchFeature
import sumicya.qself.xp.Hooks

@QselfFeature(
    id = "misc.disable_hot_patch",
    name = "禁用热补丁",
    summary = "禁用 QQ 云控热补丁 / 热更新",
    category = "misc",
)
object DisableHotPatch : SwitchFeature() {

    override val id: String = "misc.disable_hot_patch"
    override val name: String = "禁用热补丁"
    override val summary: String = "禁用 QQ 云控热补丁 / 热更新"
    override val category: FeatureCategory = FeatureCategory.MISC
    override val experimental: Boolean = false
    override val targetProcesses: Set<ProcessKind> = setOf(ProcessKind.MAIN, ProcessKind.MSF)
    override val defaultEnabled: Boolean = false

    override fun initOnce(ctx: FeatureContext): Boolean {
        val host = ctx.host

        // 9.0.35+
        host.resolve("com.tencent.rfix.lib.download.PatchDownloadTask")?.let { cls ->
            host.method(cls, "run")?.let { m ->
                m.isAccessible = true
                Hooks.beforeIfEnabled(this, m) { it.skip() }
            }
        }
        host.resolve("com.tencent.rfix.lib.engine.PatchEngineBase")?.let { cls ->
            val patchConfig = host.resolve("com.tencent.rfix.lib.config.PatchConfig") ?: return@let
            cls.declaredMethods.singleOrNull {
                it.returnType == Void.TYPE &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == String::class.java &&
                    it.parameterTypes[1] == patchConfig
            }?.let { m ->
                m.isAccessible = true
                Hooks.beforeIfEnabled(this, m) { it.skip() }
            }
        }

        // below 9.0.35
        host.resolve("com.tencent.mobileqq.msf.core.net.utils.MsfHandlePatchUtils")?.let { cls ->
            host.method(cls, "handlePatchConfig", Int::class.javaPrimitiveType, java.util.List::class.java)
                ?.let { m ->
                    m.isAccessible = true
                    Hooks.beforeIfEnabled(this, m) { it.skip() }
                }
        }
        hookConfigServlet(host)

        host.resolve("com.tencent.mobileqq.msf.core.net.patch.PatchReporter")?.let { cls ->
            cls.declaredMethods.filter {
                it.name.startsWith("report") && it.returnType == Void.TYPE
            }.forEach { m ->
                m.isAccessible = true
                Hooks.beforeIfEnabled(this, m) { it.skip() }
            }
        }
        hookLegacyHotPatch(host)
        return true
    }

    /** ConfigServlet (old MSF config): strip hot-patch entries (type == 46). */
    private fun hookConfigServlet(host: sumicya.qself.host.Host) {
        val servlet = host.resolve("com.tencent.mobileqq.config.splashlogo.ConfigServlet") ?: return
        val respGetConfig =
            host.resolve("com.tencent.mobileqq.config.struct.splashproto.ConfigurationService\$RespGetConfig")
                ?: return
        val appRuntime = host.resolve("mqq.app.AppRuntime") ?: return

        val m1 = servlet.declaredMethods.singleOrNull {
            it.returnType == Void.TYPE &&
                it.parameterTypes.size == 6 &&
                it.parameterTypes[0] == appRuntime &&
                it.parameterTypes[1] == respGetConfig &&
                it.parameterTypes[2] == Intent::class.java &&
                it.parameterTypes[3] == java.util.List::class.java &&
                it.parameterTypes[4] == IntArray::class.java &&
                it.parameterTypes[5] == java.lang.Boolean::class.javaPrimitiveType
        } ?: return
        m1.isAccessible = true
        Hooks.beforeIfEnabled(this, m1) { param ->
            val resp = param.args[1] ?: return@beforeIfEnabled
            val list = readConfigList(resp) ?: return@beforeIfEnabled
            if (list.isEmpty()) {
                return@beforeIfEnabled
            }
            list.removeAll { config -> pbInt(config) == 46 }
            if (list.isEmpty()) {
                param.skip()
            }
        }
    }

    private fun readConfigList(respGetConfig: Any): ArrayList<Any?>? {
        return try {
            val field = respGetConfig.javaClass.getDeclaredField("config_list")
            field.isAccessible = true
            val pb = field.get(respGetConfig) ?: return null
            pb.javaClass.getMethod("get").invoke(pb) as? ArrayList<*>
        } catch (t: Throwable) {
            null
        }
    }

    private fun pbInt(config: Any?): Int {
        return try {
            val field = config!!.javaClass.getDeclaredField("type")
            field.isAccessible = true
            val pb = field.get(config) ?: return 0
            pb.javaClass.getMethod("get").invoke(pb) as? Int ?: 0
        } catch (t: Throwable) {
            0
        }
    }

    /** Legacy com.tencent.hotpatch classes (very old QQ). */
    private fun hookLegacyHotPatch(host: sumicya.qself.host.Host) {
        val qqAppInterface = host.resolve("com.tencent.mobileqq.app.QQAppInterface") ?: return
        val candidates = listOf(
            "com.tencent.hotpatch.PatchFileManager",
            "com.tencent.hotpatch.c",
            "com.tencent.hotpatch.a",
            "com.tencent.hotpatch.b",
        ).mapNotNull { host.resolve(it) }
        val target = candidates.singleOrNull { klass ->
            klass.superclass == java.lang.Object::class.java &&
                klass.declaredMethods.size <= 4 &&
                klass.declaredMethods.count {
                    it.isStatic &&
                        it.returnType == Void.TYPE &&
                        it.parameterTypes.size == 1 &&
                        it.parameterTypes[0] == qqAppInterface
                } == 2
        } ?: return
        target.declaredMethods.filter {
            it.isStatic &&
                it.returnType == Void.TYPE &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == qqAppInterface
        }.forEach { m ->
            m.isAccessible = true
            Hooks.beforeIfEnabled(this, m) { it.skip() }
        }
    }

}
