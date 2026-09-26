// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself.hook

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import sumicya.qself.Catalog

/** libxposed API 102 entry. Static scope: LSPosed loads it into QQ only. */
class Entry : XposedModule() {

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        Core.onProcess(param.processName)
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (param.packageName != Catalog.QQ || !param.isFirstPackage) return
        Core.start(this, param.classLoader)
    }

    /** Old generation, still inside the running QQ: take everything back down. */
    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
        Core.stop()
        param.setSavedInstanceState(Core.process)
        return true
    }

    /** New generation. Package callbacks are not replayed, so boot from the live app. */
    override fun onHotReloaded(param: XposedModuleInterface.HotReloadedParam) {
        param.oldHookHandles.forEach { runCatching { it.unhook() } }
        Core.onProcess(param.processName)
        val loader = Core.applicationClassLoader() ?: return
        Core.start(this, loader)
        Core.replayActivity()
    }
}
