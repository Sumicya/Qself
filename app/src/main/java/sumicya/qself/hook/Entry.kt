// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself.hook

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import sumicya.qself.Catalog

/** libxposed API 102 entry. Loaded by LSPosed into QQ only (static scope). */
class Entry : XposedModule() {
    private var process: String = ""

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        process = param.processName
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (param.packageName != Catalog.QQ || !param.isFirstPackage) return
        Runtime.start(this, param.classLoader, process)
    }

    /** Old generation: tear everything down, QQ keeps running. */
    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
        Runtime.stop()
        param.setSavedInstanceState(process)
        return true
    }

    /** New generation: package callbacks are not replayed, so boot from the live app. */
    override fun onHotReloaded(param: XposedModuleInterface.HotReloadedParam) {
        for (old in param.oldHookHandles) runCatching { old.unhook() }
        process = param.processName
        val loader = Runtime.currentApplication()?.classLoader ?: return
        Runtime.start(this, loader, process)
        Runtime.replayResumed()
    }
}
