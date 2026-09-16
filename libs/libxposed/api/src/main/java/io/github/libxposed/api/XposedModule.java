package io.github.libxposed.api;

import androidx.annotation.NonNull;
import io.github.libxposed.api.annotations.XposedApiExact;
import io.github.libxposed.api.annotations.XposedApiMin;

/**
 * Super class which all Xposed module entry classes should extend.<br/>
 * Entry classes are instantiated once for each module generation in a process.
 * A hot-reloaded generation does not automatically receive the initial module
 * or package lifecycle callbacks.
 */
@SuppressWarnings("unused")
public abstract class XposedModule extends XposedInterfaceWrapper implements XposedModuleInterface {
    /**
     * Instantiates a new Xposed module.<br/>
     * Entry classes are instantiated once for each module generation in a
     * process. Modules should not do initialization work before
     * {@link #onModuleLoaded(ModuleLoadedParam)} is called.
     *
     * @param base  The implementation interface provided by the framework, should not be used by the module
     * @param param Information about the process in which the module is loaded
     */
    @XposedApiExact(100)
    public XposedModule(@NonNull XposedInterface base, @NonNull ModuleLoadedParam param) {
        throw new AssertionError("STUB");
    }

    @XposedApiMin(101)
    public XposedModule() {
        throw new AssertionError("STUB");
    }

}
