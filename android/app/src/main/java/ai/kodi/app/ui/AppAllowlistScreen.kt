package ai.kodi.app.ui

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ai.kodi.app.data.AppAllowlist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class AppEntry(
    val packageName: String,
    val label: String,
    val builtIn: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppAllowlistScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val pm = context.packageManager
    val allowlist = remember { AppAllowlist(context) }

    var entries by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    val granted = remember { mutableStateMapOf<String, Boolean>() }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) {
            loadInstalledApps(pm).sortedWith(
                compareByDescending<AppEntry> { allowlist.isAllowed(it.packageName) }
                    .thenBy { it.label.lowercase() }
            )
        }
        loaded.forEach { granted[it.packageName] = allowlist.isAllowed(it.packageName) || it.builtIn }
        entries = loaded
        loading = false
    }
    val filtered by remember {
        derivedStateOf {
            val q = query.trim().lowercase()
            if (q.isEmpty()) entries
            else entries.filter { it.label.lowercase().contains(q) || it.packageName.contains(q) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("App access") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search apps") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Text(
                "Toggle ON the apps Kodi may open and operate. Built-in tools (WhatsApp, Gmail, Maps, etc.) are always allowed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Column
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(filtered, key = { it.packageName }) { app ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(app.label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if (app.builtIn) "${app.packageName} (built-in)" else app.packageName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = granted[app.packageName] == true,
                            enabled = !app.builtIn,
                            onCheckedChange = { on ->
                                granted[app.packageName] = on
                                if (on) allowlist.grant(app.packageName) else allowlist.revoke(app.packageName)
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun loadInstalledApps(pm: PackageManager): List<AppEntry> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val resolves: List<ResolveInfo> = try {
        pm.queryIntentActivities(intent, 0)
    } catch (_: Exception) {
        emptyList()
    }
    return resolves.mapNotNull { r ->
        val pkg = r.activityInfo?.packageName ?: return@mapNotNull null
        val label = try {
            val ai: ApplicationInfo = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(ai).toString()
        } catch (_: Exception) { pkg }
        AppEntry(packageName = pkg, label = label, builtIn = pkg in AppAllowlist.BUILT_IN_PACKAGES)
    }.distinctBy { it.packageName }
}
