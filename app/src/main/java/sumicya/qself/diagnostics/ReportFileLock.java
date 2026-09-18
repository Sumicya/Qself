/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.diagnostics;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.util.concurrent.Callable;

/** Cross-process transaction lock. Only diagnostic IO workers acquire it, never sending callbacks. */
public final class ReportFileLock {
    private ReportFileLock() { }

    // Serializes same-JVM callers (FileChannel otherwise throws on overlapping local locks).
    public static synchronized <T> T withLock(File file, Callable<T> transaction) throws Exception {
        try (RandomAccessFile handle = new RandomAccessFile(file, "rw");
             FileLock lock = handle.getChannel().lock()) {
            return transaction.call();
        }
    }
}
