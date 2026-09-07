/*
 * QAuxiliary - An Xposed module for QQ/TIM
 * Copyright (C) 2019-2026 QAuxiliary developers
 * https://github.com/cinit/QAuxiliary
 *
 * This software is an opensource software: you can redistribute it
 * and/or modify it under the terms of the General Public License
 * as published by the Free Software Foundation; either
 * version 3 of the License, or any later version as published
 * by QAuxiliary contributors.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the General Public License for more details.
 *
 * You should have received a copy of the General Public License
 * along with this software.
 * If not, see
 * <https://github.com/cinit/QAuxiliary/blob/master/LICENSE.md>.
 */

package io.github.qauxv.loader.sbl.lsp10x;

import android.util.Log;
import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.annotations.XposedApiExact;
import io.github.libxposed.api.annotations.XposedApiMin;
import io.github.qauxv.loader.sbl.lsp100.Lsp100HookEntry;
import io.github.qauxv.loader.sbl.lsp101.Lsp101HookEntry;

/**
 * The unified entry point for libxpsoed API 100 and 101 (typically LSPosed).
 * <p>
 * Keep this class as simple as possible, and do not add any code that may cause NoClassDefFoundError when running on API 100 or 101.
 * <p>
 * Any fields appear here should be carefully reviewed to ensure they are both API 100 and 101 compatible.
 */
@Keep
public class Lsp10xUnifiedHookEntry extends XposedModule {

    private final Lsp10xHookEntryHandler mHandler;

    /* --- start of API 100 --- */

    @XposedApiExact(100)
    public Lsp10xUnifiedHookEntry(@NonNull XposedInterface base, @NonNull ModuleLoadedParam param) {
        super(base, param);
        // This is the early initialization constructor for API 100,
        // which is equivalent to the old onInitZygote method.
        mHandler = new Lsp100HookEntry(this, param);
    }

    /* --- end of API 100 --- */

    @Override
    public void onPackageLoaded(@NonNull XposedModule.PackageLoadedParam param) {
        sLastPackageLoaded = param;
        mHandler.onPackageLoaded(param);
    }

    /* --- start of API 101 --- */

    @RequiresApi(26)
    @XposedApiMin(101)
    public Lsp10xUnifiedHookEntry() {
        super();
        // The libxposed spec says module should not perform any initialization before onModuleLoaded is called.
        mHandler = new Lsp101HookEntry(this);
    }

    @RequiresApi(26)
    @XposedApiMin(101)
    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        ((Lsp101HookEntry) mHandler).onModuleLoaded(param);
    }

    @RequiresApi(26)
    @XposedApiMin(101)
    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        sLastPackageReady = param;
        ((Lsp101HookEntry) mHandler).onPackageReady(param);
    }

    /* --- end of API 101 --- */

    /* --- start of API 102 (hot reload) --- */

    private static final String TAG = "QAuxvLoader";

    /**
     * Last seen package lifecycle params, captured so the hot-reloading old
     * generation can hand them to the new one. They are framework-classloader
     * objects, which the API contract allows across generations.
     */
    private static volatile PackageLoadedParam sLastPackageLoaded;
    private static volatile PackageReadyParam sLastPackageReady;

    @XposedApiMin(102)
    @Override
    public boolean onHotReloading(@NonNull HotReloadingParam param) {
        // Consent to the reload and pass the last package params over. Old
        // module-owned state (hook registries, feature singletons) lives in
        // old-generation statics and is deliberately NOT transferred: the new
        // generation re-runs the full startup chain instead.
        try {
            param.setSavedInstanceState(new Object[]{sLastPackageLoaded, sLastPackageReady});
        } catch (Throwable t) {
            Log.e(TAG, "hot reloading: saved state rejected, reloading without it", t);
        }
        return true;
    }

    @XposedApiMin(102)
    @Override
    public void onHotReloaded(@NonNull HotReloadedParam param) {
        // Default semantics first: every hook the old generation made goes.
        try {
            param.getOldHookHandles().forEach(XposedInterface.HookHandle::unhook);
        } catch (Throwable t) {
            Log.w(TAG, "hot reloaded: unhook sweep failed", t);
        }
        // The package lifecycle is NOT replayed automatically - re-run it in
        // the new generation so the whole feature chain re-initialises and
        // re-hooks with fresh code.
        Object state = param.getSavedInstanceState();
        if (!(state instanceof Object[])) {
            Log.i(TAG, "hot reloaded: no saved package state; hooks dropped only");
            return;
        }
        Object[] pair = (Object[]) state;
        try {
            if (pair[0] instanceof PackageLoadedParam) {
                mHandler.onPackageLoaded((PackageLoadedParam) pair[0]);
            }
            if (pair[1] instanceof PackageReadyParam && mHandler instanceof Lsp101HookEntry) {
                ((Lsp101HookEntry) mHandler).onPackageReady((PackageReadyParam) pair[1]);
            }
            Log.i(TAG, "hot reloaded: package lifecycle replayed for the new generation");
        } catch (Throwable t) {
            Log.e(TAG, "hot reloaded: replay failed; module inactive until process restart", t);
        }
    }
}
