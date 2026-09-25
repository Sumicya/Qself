// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself.ui

import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.libxposed.service.XposedService
import sumicya.qself.Catalog

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val ctx = LocalContext.current
            val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            MaterialTheme(colorScheme = scheme) { Screen(Framework.service.value) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Screen(service: XposedService?) {
    val prefs: SharedPreferences? = remember(service) { runCatching { service?.getRemotePreferences(Catalog.PREFS) }.getOrNull() }
    val state = remember(prefs) {
        mutableStateMapOf<String, Boolean>().apply {
            Catalog.items.forEach { put(it.id, prefs?.getBoolean(it.id, it.default) ?: it.default) }
        }
    }
    DisposableEffect(prefs) {
        val l = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
            Catalog.byId[key]?.let { state[it.id] = p.getBoolean(it.id, it.default) }
        }
        prefs?.registerOnSharedPreferenceChangeListener(l)
        onDispose { prefs?.unregisterOnSharedPreferenceChangeListener(l) }
    }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = { LargeTopAppBar(title = { Text("Qself") }, scrollBehavior = scroll) },
    ) { pad ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { StatusCard(service) }
            Catalog.Group.entries.forEach { group ->
                item {
                    Text(
                        group.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp),
                    )
                }
                item {
                    Card(
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    ) {
                        Catalog.items.filter { it.group == group }.forEach { item ->
                            val on = state[item.id] ?: item.default
                            val toggle = {
                                if (prefs != null) {
                                    state[item.id] = !on
                                    prefs.edit().putBoolean(item.id, !on).apply()
                                }
                            }
                            ListItem(
                                headlineContent = { Text(item.title) },
                                supportingContent = { Text(item.summary) },
                                trailingContent = { Switch(checked = on, enabled = prefs != null, onCheckedChange = { toggle() }) },
                                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                                modifier = Modifier.clickable(enabled = prefs != null) { toggle() },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(service: XposedService?) {
    val ctx = LocalContext.current
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (service != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            if (service == null) {
                Text("未激活", style = MaterialTheme.typography.titleLarge)
                Text("在 LSPosed（libxposed API 102）里启用 Qself，作用域为 QQ。", style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            Text("已激活 · 开关即时生效", style = MaterialTheme.typography.titleLarge)
            Text(
                "${service.frameworkName} ${service.frameworkVersion} · API ${service.apiVersion}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(onClick = {
                val targets = runCatching { service.runningTargets }.getOrDefault(emptyList())
                if (targets.isEmpty()) {
                    Toast.makeText(ctx, "QQ 没在运行", Toast.LENGTH_SHORT).show()
                    return@FilledTonalButton
                }
                targets.forEach { t ->
                    service.hotReloadModule(t, null) { _, result ->
                        (ctx as? ComponentActivity)?.runOnUiThread {
                            Toast.makeText(ctx, "热重载：$result", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }) { Text("热重载 QQ 里的 Qself") }
        }
    }
}
