/*
 * QAuxiliary - An Xposed module for QQ/TIM
 * Copyright (C) 2019-2023 QAuxiliary developers
 * https://github.com/cinit/QAuxiliary
 *
 * This software is free software: you can redistribute it
 * and/or modify it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package io.github.qauxv.core;

import android.content.Context;
import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import io.github.qauxv.util.HostInfo;
import com.tencent.mmkv.MMKV;
import com.tencent.qqnt.kernel.nativeinterface.StarInfo;
import io.github.qauxv.base.RuntimeErrorTracer;
import io.github.qauxv.poststartup.StartupInfo;
import io.github.qauxv.util.Log;
import io.github.qauxv.util.soloader.NativeLoader;
import java.io.File;

public class NativeCoreBridge {

    private NativeCoreBridge() {
        throw new AssertionError("No NativeCoreBridge instances for you!");
    }

    /**
     * Perform full initialization for native core.
     * <p>
     * The primary native library MUST be loaded and pre-initialized before calling this method.
     * <p>
     * The HostInfo MUST be initialized before calling this method.
     */
    public static void initNativeCore() {
        Context context = HostInfo.getHostInfo().getApplication();
        if (StartupInfo.isInHostProcess()) {
            NativeLoader.primaryNativeLibraryFullInitialize(context);
            if (NativeLoader.isSecondaryNativeLibraryNeeded(context)) {
                NativeLoader.loadSecondaryNativeLibrary(context);
                NativeLoader.secondaryNativeLibraryFullInitialize(context);
            }
        } else {
            // in own app_process
            NativeLoader.primaryNativeLibraryFullInitialize(context);
        }
    }

    // This method is intended to be called from native code
    @Keep
    private static void nativeTraceErrorHelper(Object thiz, Throwable error) {
        if (thiz instanceof RuntimeErrorTracer) {
            RuntimeErrorTracer tracer = (RuntimeErrorTracer) thiz;
            tracer.traceError(error);
        } else {
            Log.e("NativeCoreBridge nativeTraceErrorHelper: thiz is not a RuntimeErrorTracer, got "
                    + thiz.getClass().getName() + ", errorMessage: " + error);
        }
    }

    private static boolean sPrimaryNativeLibraryInitialized = false;

    /**
     * Load native library and initialize MMKV
     *
     * @param ctx Application context
     * @throws LinkageError if failed to load native library
     */
    public static void initializeMmkvForPrimaryNativeLibrary(@NonNull Context ctx) {
        if (sPrimaryNativeLibraryInitialized) {
            return;
        }
        File dataDir = ctx.getDataDir();
        File filesDir = ctx.getFilesDir();
        File mmkvDir = new File(filesDir, "qa_mmkv");
        if (!mmkvDir.exists()) {
            mmkvDir.mkdirs();
        }
        // MMKV requires a ".tmp" cache directory, we have to create it manually
        File cacheDir = new File(mmkvDir, ".tmp");
        if (!cacheDir.exists()) {
            cacheDir.mkdir();
        }
        MMKV.initialize(ctx, mmkvDir.getAbsolutePath(), s -> {
            // nop, mmkv is attached with libqauxv-core0.so
        });
        MMKV.mmkvWithID("global_config", MMKV.MULTI_PROCESS_MODE);
        MMKV.mmkvWithID("global_cache", MMKV.MULTI_PROCESS_MODE);
        sPrimaryNativeLibraryInitialized = true;
    }

}
