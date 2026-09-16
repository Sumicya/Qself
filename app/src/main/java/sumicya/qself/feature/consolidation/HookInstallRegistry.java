/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.feature.consolidation;

import java.util.HashSet;
import java.util.Set;

/** Actual hook target identity, not receiver subclass identity. Failed installs may be retried. */
public final class HookInstallRegistry<K> {
    private final Set<K> installed = new HashSet<>();
    public interface Installer { void install() throws Throwable; }
    public synchronized boolean install(K key, Installer installer) throws Throwable {
        if (installed.contains(key)) return false;
        installer.install();
        installed.add(key);
        return true;
    }
}
