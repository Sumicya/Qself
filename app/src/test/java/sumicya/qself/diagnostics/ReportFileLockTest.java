/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.diagnostics;

import static org.junit.Assert.*;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class ReportFileLockTest {
    @Test(timeout = 30000)
    public void separateProcessesSerializeWrites() throws Exception {
        File directory = Files.createTempDirectory("qself-diagnostics-process-lock").toFile();
        File lock = new File(directory, "lock");
        File counter = new File(directory, "counter");
        File outputA = new File(directory, "a.log");
        File outputB = new File(directory, "b.log");
        String java = new File(System.getProperty("java.home"), "bin/java").getPath();
        String classpath = new File(ReportFileLock.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath()
                + File.pathSeparator + new File(ReportFileLockTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath();
        Process a = null;
        Process b = null;
        try {
            ProcessBuilder command = new ProcessBuilder(java, "-cp", classpath,
                    "sumicya.qself.diagnostics.ReportFileLockProcessProbe", lock.getPath(), counter.getPath());
            a = command.redirectErrorStream(true).redirectOutput(outputA).start();
            b = command.redirectOutput(outputB).start();
            assertTrue(a.waitFor(10, TimeUnit.SECONDS));
            assertTrue(b.waitFor(10, TimeUnit.SECONDS));
            assertEquals(Files.readString(outputA.toPath()), 0, a.exitValue());
            assertEquals(Files.readString(outputB.toPath()), 0, b.exitValue());
            try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(counter, "r")) {
                assertEquals(200, file.readInt());
            }
        } finally {
            if (a != null) { a.destroyForcibly(); a.waitFor(5, TimeUnit.SECONDS); }
            if (b != null) { b.destroyForcibly(); b.waitFor(5, TimeUnit.SECONDS); }
            for (File file : new File[]{counter, lock, outputA, outputB}) Files.deleteIfExists(file.toPath());
            Files.deleteIfExists(directory.toPath());
        }
    }

    @Test
    public void concurrentTransactionsDoNotOverlapAndExceptionReleasesLock() throws Exception {
        File directory = Files.createTempDirectory("qself-diagnostics-lock").toFile();
        File lock = new File(directory, "lock");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            AtomicInteger value = new AtomicInteger();
            CountDownLatch start = new CountDownLatch(1);
            java.util.concurrent.Callable<Void> work = () -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                for (int i = 0; i < 100; i++) ReportFileLock.withLock(lock, () -> {
                    int previous = value.get();
                    Thread.yield();
                    value.set(previous + 1);
                    return null;
                });
                return null;
            };
            Future<Void> a = pool.submit(work);
            Future<Void> b = pool.submit(work);
            start.countDown();
            a.get(15, TimeUnit.SECONDS);
            b.get(15, TimeUnit.SECONDS);
            assertEquals(200, value.get());
            try {
                ReportFileLock.withLock(lock, () -> { throw new IllegalStateException("test-only"); });
                fail("expected transaction exception");
            } catch (IllegalStateException expected) {
                // A subsequent transaction must still acquire the OS lock.
            }
            assertEquals("unlocked", ReportFileLock.withLock(lock, () -> "unlocked"));
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
            Files.deleteIfExists(lock.toPath());
            Files.deleteIfExists(directory.toPath());
        }
    }
}

/** Separate JVM test helper; does not load Android or JUnit in the child process. */
class ReportFileLockProcessProbe {
    public static void main(String[] args) throws Exception {
        File lock = new File(args[0]);
        File counter = new File(args[1]);
        for (int i = 0; i < 100; i++) ReportFileLock.withLock(lock, () -> {
            try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(counter, "rw")) {
                int previous = file.length() == 0 ? 0 : file.readInt();
                Thread.yield();
                file.seek(0);
                file.writeInt(previous + 1);
            }
            return null;
        });
    }
}
