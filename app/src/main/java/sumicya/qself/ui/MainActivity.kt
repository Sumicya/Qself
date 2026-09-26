// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.libxposed.service.XposedService
import sumicya.qself.Catalog

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)) {
                Screen(Framework.service.value)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Screen(service: XposedService?) {
    val context = LocalContext.current
    val prefs = remember(service) { runCatching { service?.getRemotePreferences(Catalog.PREFS) }.getOrNull() }
    val state = remember(prefs) {
        mutableStateMapOf<String, Boolean>().apply {
            Catalog.items.forEach { put(it.id, prefs?.getBoolean(it.id, it.default) ?: it.default) }
        }
    }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
            Catalog.byId[key]?.let { state[it.id] = p.getBoolean(it.id, it.default) }
        }
        prefs?.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs?.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = { LargeTopAppBar(title = { Text("Qself") }, scrollBehavior = scroll) },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Card(shape = RoundedCornerShape(28.dp), colors = statusColors(service)) { Status(service) } }
            item { Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) { Report() } }
            Catalog.Group.entries.filter { g -> Catalog.items.any { it.group == g } }.forEach { group ->
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
                                    Reloader.schedule(context)
                                }
                            }
                            ListItem(
                                headlineContent = { Text(item.title) },
                                supportingContent = { if (item.summary.isNotEmpty()) Text(item.summary) },
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

/**
 * Hooks may only be installed while QQ's package is loading, so flipping a switch writes the
 * preference and then asks LSPosed to hot reload the module inside the running QQ: the old
 * generation tears its hooks down, the new one installs whatever the preferences now say.
 * Flipping several switches in a row is collapsed into one reload.
 */
private object Reloader {
    private val handler = Handler(Looper.getMainLooper())
    private var context: Context? = null
    private var service: XposedService? = null
    private val fire = Runnable {
        val svc = service ?: return@Runnable
        val ctx = context ?: return@Runnable
        val targets = runCatching { svc.runningTargets }.getOrDefault(emptyList())
        if (targets.isEmpty()) return@Runnable
        targets.forEach { target -> runCatching { svc.hotReloadModule(target, null) { _, _ -> } } }
        Toast.makeText(ctx, "已让 QQ 里的 Qself 热重载", Toast.LENGTH_SHORT).show()
    }

    fun schedule(ctx: Context) {
        context = ctx
        service = Framework.service.value
        handler.removeCallbacks(fire)
        handler.postDelayed(fire, 500)
    }
}

@Composable
private fun statusColors(service: XposedService?) = CardDefaults.cardColors(
    containerColor = if (service != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
)

@Composable
private fun Status(service: XposedService?) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(20.dp)) {
        if (service == null) {
            Text("未激活", style = MaterialTheme.typography.titleLarge)
            Text("在 LSPosed（libxposed API 102）里启用 Qself，作用域是 QQ。", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        Text("已激活 · 开关即时生效", style = MaterialTheme.typography.titleLarge)
        Text("${service.frameworkName} ${service.frameworkVersion} · API ${service.apiVersion}", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        FilledTonalButton(onClick = {
            val targets = runCatching { service.runningTargets }.getOrDefault(emptyList())
            if (targets.isEmpty()) {
                Toast.makeText(context, "QQ 没在运行", Toast.LENGTH_SHORT).show()
                return@FilledTonalButton
            }
            targets.forEach { target ->
                service.hotReloadModule(target, null) { _, result ->
                    (context as? Activity)?.runOnUiThread { Toast.makeText(context, "热重载：$result", Toast.LENGTH_SHORT).show() }
                }
            }
        }) { Text("热重载 QQ 里的 Qself") }
    }
}

/** Asks the running QQ for its report, so problems can be pasted instead of dug out of files. */
@Composable
private fun Report() {
    val context = LocalContext.current
    var text by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(20.dp)) {
        Text("自检报告", style = MaterialTheme.typography.titleMedium)
        Text("从正在运行的 QQ 里取回每个开关的状态、失败原因和钩子数。", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        FilledTonalButton(enabled = !busy, onClick = {
            busy = true
            context.sendOrderedBroadcast(Intent(Catalog.ACTION_REPORT).setPackage(Catalog.QQ), null, object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    busy = false
                    text = resultData ?: "QQ 没有回应。\n先打开 QQ 随便点一下（让 Qself 在 QQ 里跑起来），再回来点这个按钮。"
                }
            }, null, Activity.RESULT_OK, null, null)
        }) { Text(if (busy) "正在询问 QQ…" else "生成报告") }
    }

    text?.let { body ->
        AlertDialog(
            onDismissRequest = { text = null },
            title = { Text("自检报告") },
            text = {
                SelectionContainer(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                    Text(body, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Qself 自检报告", body))
                    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                }) { Text("复制") }
            },
            dismissButton = { TextButton(onClick = { text = null }) { Text("关闭") } },
        )
    }
}
