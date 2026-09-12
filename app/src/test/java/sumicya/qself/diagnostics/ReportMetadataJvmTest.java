/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.diagnostics;

import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/** Runs the standalone regression suite in the full Gradle test task as well. */
public class ReportMetadataJvmTest {
    @Test
    public void metadataContract() throws Exception {
        ReportMetadataTest.main(new String[0]);
    }
    @Test
    public void sourceContractGuardrails() throws Exception {
        Path script = Paths.get("scripts/test_report_diagnostics_contract.py");
        if (!Files.exists(script)) script = Paths.get("../scripts/test_report_diagnostics_contract.py");
        assertTrue("static guard script missing", Files.exists(script));
        Process process = new ProcessBuilder("python3", script.toAbsolutePath().toString())
                .redirectErrorStream(true).start();
        try {
            assertTrue("static guards timed out", process.waitFor(15, TimeUnit.SECONDS));
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(output, 0, process.exitValue());
        } finally {
            process.destroyForcibly();
        }
    }
}
