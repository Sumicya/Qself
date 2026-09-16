/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.feature.consolidation;

import org.junit.Test;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class ConsolidationTest {
    @Test public void tailPriorityCoversEverySwitchCombination() {
        for (boolean detail : new boolean[]{false,true}) for (boolean warning : new boolean[]{false,true})
            for (boolean noSeq : new boolean[]{false,true}) for (boolean gray : new boolean[]{false,true}) {
                MessageTailPolicy.Kind expected = gray ? MessageTailPolicy.Kind.NONE :
                    warning && noSeq ? MessageTailPolicy.Kind.DELIVERY_WARNING :
                    detail ? MessageTailPolicy.Kind.DETAILS : MessageTailPolicy.Kind.NONE;
                assertEquals(expected, MessageTailPolicy.resolve(detail, warning, noSeq, gray));
            }
    }
    @Test public void reusedTailTransitionsBackToDetailsAndThenClears() {
        assertEquals(MessageTailPolicy.Kind.DELIVERY_WARNING, MessageTailPolicy.resolve(true,true,true,false));
        assertEquals(MessageTailPolicy.Kind.DETAILS, MessageTailPolicy.resolve(true,true,false,false));
        assertEquals(MessageTailPolicy.Kind.NONE, MessageTailPolicy.resolve(false,true,false,false));
        assertEquals(MessageTailPolicy.Kind.NONE, MessageTailPolicy.resolve(true,true,true,true));
    }
    public static class Parent { public void menu() {} }
    public static class First extends Parent {}
    public static class Second extends Parent {}
    public static class Overridden extends Parent { @Override public void menu() {} }

    @Test public void inheritedMenuMethodIsInstalledOnceButOverrideIsIndependent() throws Throwable {
        HookInstallRegistry<Method> registry = new HookInstallRegistry<>();
        AtomicInteger installs = new AtomicInteger();
        assertTrue(registry.install(First.class.getMethod("menu"), installs::incrementAndGet));
        assertFalse(registry.install(Second.class.getMethod("menu"), installs::incrementAndGet));
        assertTrue(registry.install(Overridden.class.getMethod("menu"), installs::incrementAndGet));
        assertEquals(2, installs.get());
    }
    @Test public void failedInstallDoesNotPoisonRetry() throws Throwable {
        HookInstallRegistry<String> registry = new HookInstallRegistry<>();
        try { registry.install("menu", () -> { throw new IllegalStateException("test failure"); }); fail(); }
        catch (IllegalStateException expected) { }
        assertTrue(registry.install("menu", () -> {}));
        assertFalse(registry.install("menu", () -> fail()));
    }
    @Test public void concurrentConstructorsCannotDoubleInstall() throws Exception {
        HookInstallRegistry<String> registry = new HookInstallRegistry<>();
        AtomicInteger installs = new AtomicInteger();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int i=0; i<12; i++) {
            Thread t = new Thread(() -> {
                try { start.await(); registry.install("menu", installs::incrementAndGet); }
                catch (Throwable e) { error.set(e); }
            }); threads.add(t); t.start();
        }
        start.countDown();
        for (Thread t : threads) { t.join(5000); assertFalse(t.isAlive()); }
        assertNull(error.get()); assertEquals(1, installs.get());
    }
    @Test public void everyCapabilityHasOneCanonicalSearchAndSettingsPath() {
        Set<String> ids = new HashSet<>();
        for (FeatureCatalog.Group group : FeatureCatalog.getGroups()) {
            for (FeatureCatalog.Section section : group.getSections()) for (String id : section.getFeatures()) {
                assertTrue("duplicate: " + id, ids.add(id));
                assertArrayEquals(new String[]{group.getPath()[0],group.getId(),section.getId()}, FeatureCatalog.locationFor(id));
                assertNotNull(FeatureCatalog.groupPath(group.getId()));
            }
        }
        assertTrue(ids.contains("nep.timeline.PromptForNoSeqMessage"));
        assertTrue(ids.contains("cc.ioctl.hook.msg.CopyCardMsg"));
        assertFalse(ids.contains("sumicya.qself.feature.consolidation.AdPurifySuite"));
        assertArrayEquals(new String[]{"features","qself-purify","qself-purify-ads"},
            FeatureCatalog.locationFor("io.github.relimus.hook.HideQZoneAD"));
    }
    @Test public void routeLookupReturnsDefensiveCopies() {
        String id = "me.ketal.hook.ChatItemShowQQUin";
        String[] path = FeatureCatalog.locationFor(id); path[0] = "corrupted";
        assertEquals("features", FeatureCatalog.locationFor(id)[0]);
        assertNull(FeatureCatalog.locationFor("missing"));
    }
    @Test public void malformedOrDuplicateCatalogIsRejected() {
        for (String[] rows : new String[][]{
            {"bad"}, {"g\tchat\tGroup\ts\tSection\tFeature", "g\tchat\tGroup\ts\tSection\tFeature"},
            {"g\tchat\tGroup\ts\tSection\tOne", "g\ttools\tOther\ts\tSection\tTwo"}
        }) {
            try { FeatureCatalog.parse(rows); fail("invalid catalog accepted"); }
            catch (IllegalArgumentException expected) { }
        }
    }
}
