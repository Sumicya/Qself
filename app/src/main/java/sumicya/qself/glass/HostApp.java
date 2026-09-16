/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.glass;

/**
 * QQ-specific handles the glass layer needs to locate the bottom tab bar.
 *
 * <p>Resource ids are renamed by resource obfuscation, so identification
 * relies on stable class/method names. Server switches can change which bar
 * implementation is live; both candidates are listed, and the installer
 * probes them in order. Anything describing another host does not belong
 * here — Qself ships for QQ only.</p>
 */
public final class HostApp {

    public static final HostApp QQ = new HostApp(
            "com.tencent.mobileqq",
            "com.tencent.mobileqq.activity.SplashActivity",
            new String[]{
                    "com.tencent.mobileqq.widget.QQTabWidget",
                    "com.tencent.mobileqq.widget.QQTabLayout",
            },
            new String[]{"setCurrentTab"},
            "getCurrentTab",
            new String[]{"com.tencent.qui.quiblurview.QQBlurViewWrapper"},
            new String[]{"TabDragAnimationView"},
            true,
            "com.tencent.mobileqq.");

    private static final HostApp[] KNOWN = {QQ};

    public final String pkg;
    /** Home activity; the only activity the bar work reacts to. */
    public final String launcherActivity;
    /** Candidate bar view classes, most likely first. */
    final String[] tabViewClasses;
    /** Selection methods invoked on the bar on every page switch. */
    final String[] tabSwitchMethods;
    /** Zero-arg selected-index getter when the bar exposes one, else null. */
    final String currentIndexMethod;
    /** Opaque strips behind the bar that must not survive between glass and page. */
    final String[] hiddenSiblings;
    /** Class-name suffixes identifying tab icon views across obfuscation. */
    final String[] iconClassSuffixes;
    /**
     * QQ ships its own skin night mode which may disagree with the system
     * uiMode; when true, label colour is the authoritative dark signal.
     */
    final boolean preferTextColorProbe;
    /** Prefix used to compress view-tree diagnostics. */
    final String uiPrefix;

    private HostApp(String pkg, String launcherActivity, String[] tabViewClasses,
                    String[] tabSwitchMethods, String currentIndexMethod,
                    String[] hiddenSiblings, String[] iconClassSuffixes,
                    boolean preferTextColorProbe, String uiPrefix) {
        this.pkg = pkg;
        this.launcherActivity = launcherActivity;
        this.tabViewClasses = tabViewClasses;
        this.tabSwitchMethods = tabSwitchMethods;
        this.currentIndexMethod = currentIndexMethod;
        this.hiddenSiblings = hiddenSiblings;
        this.iconClassSuffixes = iconClassSuffixes;
        this.preferTextColorProbe = preferTextColorProbe;
        this.uiPrefix = uiPrefix;
    }

    static HostApp forProcess(String processName) {
        return processName != null && QQ.pkg.equals(processName) ? QQ : null;
    }

    static HostApp forPackage(String packageName) {
        return QQ.pkg.equals(packageName) ? QQ : null;
    }

    boolean isTabViewClass(String className) {
        return contains(tabViewClasses, className);
    }

    boolean isHiddenSibling(String className) {
        return contains(hiddenSiblings, className);
    }

    boolean isTabIconClass(String className) {
        if (className == null) {
            return false;
        }
        for (String suffix : iconClassSuffixes) {
            if (className.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(String[] values, String wanted) {
        if (wanted == null) {
            return false;
        }
        for (String v : values) {
            if (v.equals(wanted)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return pkg;
    }
}
