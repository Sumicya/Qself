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

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Drift guard for the P1 feature-reorg roadmap (docs/refactoring/05): the
 * checked-in inventory manifest is the deliberate-change ledger. Adding,
 * removing, renaming, or re-categorising any @UiItemAgentEntry entry MUST go
 * through a manifest update in the same commit - the reorg batches move
 * entries by editing the manifest first, and this test keeps the tree and
 * the ledger from silently diverging (the same discipline LayoutFqcnGuardTest
 * applies to layout FQCNs).
 *
 * <p>Manifest format: one line per entry, tab-separated
 * {@code relative/path.kt<TAB>display name or -<TB>Locations token or -}.
 *
 * <p>Extraction is intentionally the same simple static scan the audit used:
 * five naming shapes and a Locations token; anything dynamic (names built at
 * runtime, getUiItemLocation() returning constants from elsewhere) records
 * as "-" and stays legal - the ledger tracks existence and category, not
 * every display nuance.
 *
 * <p>To regenerate after an intentional change: run with
 * {@code -Dfeature.inventory.regenerate=true} once, then review the diff.
 */
public class FeatureInventoryGuardTest {

    private static final Pattern NAME_TYPED = Pattern.compile("override val name: String = \"([^\"]+)\"");
    private static final Pattern NAME_PLAIN = Pattern.compile("override val name = \"([^\"]+)\"");
    private static final Pattern NAME_GET = Pattern.compile("getName\\([\\s\\S]{0,200}?return \"([^\"]+)\"");
    private static final Pattern NAME_PREF_TITLE_TYPED = Pattern.compile("override val preferenceTitle: String = \"([^\"]+)\"");
    private static final Pattern NAME_PREF_TITLE = Pattern.compile("override val preferenceTitle = \"([^\"]+)\"");
    private static final Pattern LOCATION = Pattern.compile("Locations\\.([A-Za-z_]+)");
    private static final Pattern ENTRY_ANNOTATION = Pattern.compile("@UiItemAgentEntry");

    private static Path sourceRoot() {
        for (String candidate : new String[]{"src/main/java", "app/src/main/java"}) {
            Path p = Paths.get(candidate);
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        throw new IllegalStateException("source root not found (user.dir=" + System.getProperty("user.dir") + ")");
    }

    private static Path manifestPath() {
        for (String candidate : new String[]{
                "src/test/resources/feature_inventory.json", "app/src/test/resources/feature_inventory.json"}) {
            Path p = Paths.get(candidate);
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        throw new IllegalStateException("feature inventory manifest not found");
    }

    private static String firstGroup(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static String scanFile(Path file, Path root) throws IOException {
        String t = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        if (!ENTRY_ANNOTATION.matcher(t).find()) {
            return null;
        }
        String name = firstGroup(NAME_TYPED, t);
        if (name == null) {
            name = firstGroup(NAME_PLAIN, t);
        }
        if (name == null) {
            name = firstGroup(NAME_GET, t);
        }
        if (name == null) {
            name = firstGroup(NAME_PREF_TITLE_TYPED, t);
        }
        if (name == null) {
            name = firstGroup(NAME_PREF_TITLE, t);
        }
        String loc = firstGroup(LOCATION, t);
        return root.relativize(file).toString().replace('\\', '/')
                + "\t" + (name == null ? "-" : name)
                + "\t" + (loc == null ? "-" : loc) + "\n";
    }

    private static String scanTree() throws IOException {
        Path root = sourceRoot();
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".kt") || p.toString().endsWith(".java"))
                    .sorted()
                    .forEach(files::add);
        }
        StringBuilder sb = new StringBuilder();
        for (Path f : files) {
            String line = scanFile(f, root);
            if (line != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    @Test
    public void manifestMatchesTree() throws IOException {
        String actual = scanTree();
        String manifest = new String(Files.readAllBytes(manifestPath()), StandardCharsets.UTF_8);
        if (Boolean.getBoolean("feature.inventory.regenerate")) {
            Files.write(manifestPath(), actual.getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (!manifest.equals(actual)) {
            throw new AssertionError("feature inventory drifted from the manifest.\n"
                    + "If the change is intentional: update app/src/test/resources/feature_inventory.json "
                    + "in the same commit (regenerate with -Dfeature.inventory.regenerate=true).\n"
                    + "--- manifest (first 40 lines) ---\n" + head(manifest, 40)
                    + "--- tree (first 40 lines) ---\n" + head(actual, 40));
        }
    }

    private static String head(String s, int lines) {
        String[] parts = s.split("\n", lines + 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(lines, parts.length); i++) {
            sb.append(parts[i]).append('\n');
        }
        return sb.toString();
    }
}
