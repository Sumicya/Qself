/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.feature.consolidation

import io.github.qauxv.base.IUiItemAgent
import io.github.qauxv.base.IUiItemAgentProvider
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.dsl.func.UiItemAgentDescription
import org.junit.Assert.*
import org.junit.Test

class ConsolidatedRoutingTest {
    private class Provider(override val itemAgentProviderUniqueIdentifier: String) : IUiItemAgentProvider {
        override val uiItemAgent: IUiItemAgent get() = error("Tree assembly must not read or toggle a feature's UI/config")
        override val uiItemLocation = arrayOf("@any-cast", "old-category")
    }

    @Test fun everyProviderAppearsOnceAtItsCanonicalLocationWithoutBeingWrapped() {
        val providers: Array<IUiItemAgentProvider> = FeatureCatalog.groups.flatMap { it.sections }
            .flatMap { it.features }.map { Provider(it) }.toTypedArray()
        val tree = FunctionEntryRouter.buildCatalogTree(providers)
        for (provider in providers) {
            val path = FeatureCatalog.locationFor(provider.itemAgentProviderUniqueIdentifier)!!
            val full = arrayOf(*path, provider.itemAgentProviderUniqueIdentifier)
            val node = tree.lookupHierarchy(full) as UiItemAgentDescription
            assertSame(provider, node.itemAgentProvider)
            assertArrayEquals(full, tree.findLocationByIdentifier(provider.itemAgentProviderUniqueIdentifier))
            assertArrayEquals(arrayOf("@any-cast", "old-category"), provider.uiItemLocation)
            assertArrayEquals(path, FunctionEntryRouter.locationForProvider(provider))
        }
        assertNotNull(tree.lookupHierarchy(arrayOf("module-config", "cfg-backup-restore")))
        assertNotNull(tree.lookupHierarchy(arrayOf("debug-category", "debug-impl")))
        assertNotNull(tree.lookupHierarchy(arrayOf("other-config", "other-about")))
    }

    @Test fun missingProviderFailsInsteadOfSilentlyDroppingACapability() {
        try {
            FunctionEntryRouter.buildCatalogTree(emptyArray())
            fail("Missing capability silently ignored")
        } catch (_: IllegalStateException) { }
    }
}
