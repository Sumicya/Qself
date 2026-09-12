/*
 * Vendored from liuran001/WeChat-LiquidGlass (MIT).
 * Entry rewritten for this repository: the libxposed API 102 module shell is
 * replaced by a plain static hub on top of XposedBridge (xpcompat). The
 * rendering/internals files are imported unmodified.
 */
package sumicya.qself.glass;

import android.app.Activity;
import android.app.Instrumentation;

import io.github.qauxv.util.xpcompat.XC_MethodHook;
import io.github.qauxv.util.xpcompat.XposedBridge;

import java.lang.reflect.Member;

/**
 * Runtime hub for the vendored liquid glass subsystem. Keeps the upstream
 * class name so the vendored files' references stay untouched; only the
 * libxposed hook plumbing is swapped for XposedBridge.
 *
 * <p>PROTECTIVE semantics are preserved: a throwable escaping a callback is
 * logged and swallowed, never surfaced in the host's own frame.</p>
 */
public final class LiquidGlassModule {

    public static final String TAG = "LiquidGlass";

    /** The app this process belongs to; null until the feature attaches one. */
    private static volatile HostApp sApp;

    /** Set by the feature object before anything else runs. */
    public static void attach(HostApp app) {
        sApp = app;
    }

    public static HostApp app() {
        return sApp;
    }

    /** Runs fn AFTER the original, ignoring and keeping its result. */
    public static void hookAfter(Member m, AfterCallback fn) {
        XposedBridge.hookMethod(m, new XC_MethodHook(50) {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    fn.after(param);
                } catch (Throwable t) {
                    logErr("after-hook failed", t);
                }
            }
        });
    }

    /**
     * Runs fn BEFORE the original. The callback may rewrite {@code param.args},
     * or call {@code param.setResult(..)} to skip the original call entirely
     * (substituting its result); returning without touching the result lets
     * the original proceed. This covers everything the upstream chain-based
     * intercept did: proceed == leave the result alone, swallow ==
     * setResult(null) in a before-advice.
     */
    public static void hookIntercept(Member m, BeforeCallback fn) {
        XposedBridge.hookMethod(m, new XC_MethodHook(50) {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    fn.before(param);
                } catch (Throwable t) {
                    logErr("intercept-hook failed", t);
                }
            }
        });
    }

    /** After-advice: runs once the original has returned. */
    public interface AfterCallback {
        void after(XC_MethodHook.MethodHookParam param) throws Throwable;
    }

    /** Before-advice: may rewrite args or substitute the result. */
    public interface BeforeCallback {
        void before(XC_MethodHook.MethodHookParam param) throws Throwable;
    }

    /** The resume trigger the installer schedules from. */
    public static Member resumeHookTarget() throws NoSuchMethodException {
        return Instrumentation.class.getMethod("callActivityOnResume", Activity.class);
    }

    public static void log(int prio, String msg) {
        android.util.Log.println(prio, TAG, msg);
    }

    public static void logErr(String msg, Throwable t) {
        android.util.Log.e(TAG, msg, t);
    }

    private LiquidGlassModule() {
    }
}
