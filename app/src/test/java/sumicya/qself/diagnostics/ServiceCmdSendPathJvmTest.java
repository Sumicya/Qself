/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.diagnostics;

import org.junit.Test;

/** Runs the send-path regression suite in the full Gradle test task as well. */
public class ServiceCmdSendPathJvmTest {
    @Test
    public void sendPathContract() throws Exception {
        ServiceCmdSendPathTest.main(new String[0]);
    }
}
