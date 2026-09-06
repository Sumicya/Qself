/*
 * QAuxiliary - An Xposed module for QQ/TIM
 * Copyright (C) 2019-2022 qwq233@qwq2333.top
 * https://github.com/cinit/QAuxiliary
 *
 * This software is non-free but opensource software: you can redistribute it
 * and/or modify it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation; either
 * version 3 of the License, or any later version and our eula as published
 * by QAuxiliary contributors.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * and eula along with this software.  If not, see
 * <https://www.gnu.org/licenses/>
 * <https://github.com/cinit/QAuxiliary/blob/master/LICENSE.md>.
 */

package sumicya.qself.guard;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.Test;

/**
 * Tripwire for the P1 regression found by the first real-device smoke test:
 * the cc.ioctl.util.ui package move swept every .java/.kt reference but
 * missed the FQCN inside activity_settings_ui_host.xml — the settings page
 * crashed with ClassNotFoundException at inflate time, a failure mode
 * invisible to both the JVM tests and assembleDebug (AAPT does not resolve
 * custom-view class names).
 *
 * Rule: every layout tag whose class name is dotted AND falls under one of
 * this project's own source namespaces must resolve to a source file under
 * src/main/java. External namespaces (androidx.*, com.google.android.*) are
 * skipped.
 */
public class LayoutFqcnGuardTest {

    /** Top namespaces that belong to this project's own source tree. */
    private static final String[] OWN_PREFIXES = {
        "io.github.", "cc.", "me.", "xyz.", "moe.", "top.", "sumicya.",
        "com.alphi.", "com.xiaoniu.", "com.enlysure.", "com.likejson.",
    };

    /** XML elements whose tag contains a dot: custom views by FQCN. */
    private static final Pattern DOTTED_TAG = Pattern.compile("<([a-zA-Z][\\w.]*\\.[\\w.]+)[\\s/>]");

    private static Path layoutDir() {
        for (String candidate : new String[]{"src/main/res/layout", "app/src/main/res/layout"}) {
            Path p = Paths.get(candidate);
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        throw new IllegalStateException("layout dir not found (user.dir=" + System.getProperty("user.dir") + ")");
    }

    private static Path sourceRoot() {
        for (String candidate : new String[]{"src/main/java", "app/src/main/java"}) {
            Path p = Paths.get(candidate);
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        throw new IllegalStateException("source root not found");
    }

    private static boolean isOwnNamespace(String fqcn) {
        for (String prefix : OWN_PREFIXES) {
            if (fqcn.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void everyOwnNamespacedViewTagResolvesToSource() throws IOException {
        Path layouts = layoutDir();
        Path sources = sourceRoot();
        List<String> violations = new ArrayList<>();
        int checked = 0;
        try (Stream<Path> files = Files.list(layouts)) {
            for (Path xml : (Iterable<Path>) files::iterator) {
                String content = new String(Files.readAllBytes(xml), java.nio.charset.StandardCharsets.UTF_8);
                Matcher m = DOTTED_TAG.matcher(content);
                while (m.find()) {
                    String fqcn = m.group(1);
                    if (!isOwnNamespace(fqcn)) {
                        continue;
                    }
                    checked++;
                    Path asJava = sources.resolve(fqcn.replace('.', '/') + ".java");
                    Path asKotlin = sources.resolve(fqcn.replace('.', '/') + ".kt");
                    if (!Files.exists(asJava) && !Files.exists(asKotlin)) {
                        violations.add(xml.getFileName() + ": <" + fqcn + "> has no source file");
                    }
                }
            }
        }
        assertTrue(
                "stale custom-view FQCNs in layouts ( ClassNotFoundException at inflate time ): "
                        + violations + " (checked " + checked + " own-namespaced tags)",
                violations.isEmpty());
        // the guard must actually be exercising the tree
        assertTrue("no own-namespaced view tags found - guard is blind, fix the prefixes", checked > 0);
    }
}
