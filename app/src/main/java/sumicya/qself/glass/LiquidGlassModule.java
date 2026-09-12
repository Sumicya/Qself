/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.glass;

import android.app.Activity;
import android.app.Instrumentation;

import io.github.qauxv.util.xpcompat.XC_MethodHook;
import io.github.qauxv.util.xpcompat.XposedBridge;

import java.lang.reflect.Member;

/**
 * Static hub connecting the glass renderer to the host process. Wraps
 * XposedBridge advice so a render bug can never escape into QQ's own call
 * frames: exceptions inside a glass callback are recorded and swallowed.
 */
public final class LiquidGlassModule {

    public static final String TAG = "LiquidGlass";

    private static volatile HostApp host;

    /** The descriptor of the host this process runs, set by the feature. */
    public static void attach(HostApp app) {
        host = app;
    }

    public static HostApp app() {
        return host;
    }

    /** Advice that runs after the hooked method, keeping its result. */
    public interface AfterCallback {
        void after(XC_MethodHook.MethodHookParam param) throws Throwable;
    }

    /** Advice that runs first; it may rewrite args or substitute the result. */
    public interface BeforeCallback {
        void before(XC_MethodHook.MethodHookParam param) throws Throwable;
    }

    public static void hookAfter(Member target, AfterCallback callback) {
        XposedBridge.hookMethod(target, new XC_MethodHook(50) {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    callback.after(param);
                } catch (Throwable t) {
                    logErr("after advice failed", t);
                }
            }
        });
    }

    public static void hookIntercept(Member target, BeforeCallback callback) {
        XposedBridge.hookMethod(target, new XC_MethodHook(50) {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    callback.before(param);
                } catch (Throwable t) {
                    logErr("before advice failed", t);
                }
            }
        });
    }

    /** Framework resume method the installer uses as its per-foreground trigger. */
    public static Member resumeHookTarget() throws NoSuchMethodException {
        return Instrumentation.class.getMethod("callActivityOnResume", Activity.class);
    }

    public static void log(int priority, String message) {
        android.util.Log.println(priority, TAG, message);
    }

    public static void logErr(String message, Throwable t) {
        sumicya.qself.diagnostics.FeatureJournal.error("sumicya.qself.glass", t);
        android.util.Log.e(TAG, message, t);
    }

    private LiquidGlassModule() {
    }
}
