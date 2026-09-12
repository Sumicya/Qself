#!/usr/bin/env python3
import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
MAIN = (ROOT / 'app/src/main/java/io/github/qauxv/fragment/SettingsMainFragment.kt').read_text()
CATALOG = (ROOT / 'app/src/main/java/sumicya/qself/ui/HomeCatalog.kt').read_text()
ROUTER = (ROOT / 'app/src/main/java/io/github/qauxv/dsl/FunctionEntryRouter.kt').read_text()

class SettingsUiContract(unittest.TestCase):
    def test_native_search_menu_and_back_lifecycle_are_registered(self):
        body = MAIN.split('override fun onCreate(savedInstanceState: Bundle?)')[1].split('override fun onViewCreated')[0]
        self.assertIn('setHasOptionsMenu(true)', body)
        self.assertIn('onBackPressedDispatcher.addCallback(this, mSearchModeOnBackPressedCallback)', body)
        self.assertIn('mSearchMenuItem = menu.findItem(R.id.menu_item_action_search)', MAIN)
        self.assertIn('item.expandActionView()', MAIN)

    def test_catalog_shortcuts_have_real_registered_sources(self):
        ids = re.findall(r'"([a-z][a-z0-9_.]+\.[A-Z][A-Za-z0-9]+)"', CATALOG)
        self.assertEqual(len(ids), len(set(ids)))
        for name in ids:
            sources = [ROOT / 'app/src/main/java' / (name.replace('.', '/') + ext) for ext in ('.kt', '.java')]
            self.assertTrue(any(p.is_file() and '@UiItemAgentEntry' in p.read_text() for p in sources), name)

    def test_utility_routes_are_real_and_do_not_write_feature_config(self):
        body = MAIN.split('private fun openHomeAction(')[1].split('override fun onCreateOptionsMenu')[0]
        for method in re.findall(r'FunctionEntryRouter\.(\w+)\(', body):
            self.assertRegex(ROUTER, r'fun\s+' + re.escape(method) + r'\s*\(')
        for route in ('cfg-theme', 'cfg-backup-restore', 'other-about'):
            self.assertIn('"' + route + '"', ROUTER)
            self.assertIn('"' + route + '"', body)
        for mutation in ('isEnabled =', 'ConfigManager', 'getDefaultConfig(', 'switchProvider'):
            self.assertNotIn(mutation, body)
        self.assertIn('UiAgentItem(endNode.identifier, endNode.name, endNode.itemAgentProvider)', MAIN)

    def test_search_and_observers_die_with_the_view(self):
        self.assertIn('viewLifecycleOwner.lifecycleScope', MAIN)
        body = MAIN.split('override fun onDestroyView()')[1].split('private fun convertFragment')[0]
        self.assertIn('abortSearchMode()', body)
        self.assertIn('mSearchMenuItem = null', body)
        self.assertIn('adapter = null', body)
        self.assertNotIn('Thread.sleep', MAIN)

if __name__ == '__main__':
    unittest.main()
