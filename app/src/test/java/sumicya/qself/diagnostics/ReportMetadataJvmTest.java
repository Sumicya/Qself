/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.diagnostics;

import org.junit.Test;

/** Runs the standalone regression suite in the full Gradle test task as well. */
public class ReportMetadataJvmTest {
    @Test
    public void metadataContract() throws Exception {
        ReportMetadataTest.main(new String[0]);
    }
}
