package sh.anirudhgupta109.pxn.ui.hiddenapps

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import sh.anirudhgupta109.pxn.R
import sh.anirudhgupta109.pxn.data.PrefStore
import sh.anirudhgupta109.pxn.util.HiddenApps

private data class AppEntry(val packageName: String, val label: String)

/**
 * Settings > Custom Settings > Hidden apps.
 *
 * Shows the apps hidden from Recents (the CSV preference [HiddenApps.PREF_KEY],
 * the same one the Pixel Launcher hook reads) with a button to show each one again,
 * a "show all" action and a "+" button to hide an app that is not in Recents right now.
 */
@Composable
fun HiddenAppsScreen() {
	val context = LocalContext.current
	val store: PrefStore = koinInject()

	val revision by store.revision.collectAsState()
	@Suppress("UNUSED_VARIABLE") val ignored = revision

	val hidden = HiddenApps.parse(store.getString(HiddenApps.PREF_KEY, ""))
	var showAdd by remember { mutableStateOf(false) }

	fun save(list: List<String>) = store.putString(HiddenApps.PREF_KEY, HiddenApps.join(list))

	val entries = hidden.map { AppEntry(it, labelOf(context, it)) }.sortedBy { it.label.lowercase() }

	Box(modifier = Modifier.fillMaxSize()) {
		if (entries.isEmpty()) {
			Column(
				modifier = Modifier.fillMaxSize().padding(32.dp),
				verticalArrangement = Arrangement.Center,
				horizontalAlignment = Alignment.CenterHorizontally,
			) {
				Text(
					text = stringResource(R.string.hidden_apps_empty),
					style = MaterialTheme.typography.bodyLarge,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		} else {
			LazyColumn(
				modifier = Modifier.fillMaxSize(),
				contentPadding = PaddingValues(bottom = 96.dp),
			) {
				item(key = "show_all") {
					Row(
						modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
						horizontalArrangement = Arrangement.End,
					) {
						TextButton(onClick = { save(emptyList()) }) {
							Text(stringResource(R.string.hidden_apps_show_all))
						}
					}
				}
				items(entries, key = { it.packageName }) { entry ->
					ListItem(
						leadingContent = { AppIcon(entry.packageName) },
						headlineContent = { Text(entry.label) },
						supportingContent = { Text(entry.packageName) },
						trailingContent = {
							TextButton(onClick = { save(hidden.filter { it != entry.packageName }) }) {
								Text(stringResource(R.string.hidden_apps_unhide))
							}
						},
					)
				}
			}
		}

		ExtendedFloatingActionButton(
			onClick = { showAdd = true },
			icon = { Icon(Icons.Default.Add, contentDescription = null) },
			text = { Text(stringResource(R.string.hidden_apps_add)) },
			modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
		)
	}

	if (showAdd) {
		AddAppDialog(
			alreadyHidden = hidden,
			onPick = { pkg ->
				save(hidden + pkg)
				showAdd = false
			},
			onDismiss = { showAdd = false },
		)
	}
}

@Composable
private fun AddAppDialog(
	alreadyHidden: List<String>,
	onPick: (String) -> Unit,
	onDismiss: () -> Unit,
) {
	val context = LocalContext.current

	val apps by produceState<List<AppEntry>?>(initialValue = null) {
		value = withContext(Dispatchers.Default) {
			val pm = context.packageManager
			val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
			pm.queryIntentActivities(launcherIntent, 0)
				.map { it.activityInfo.packageName }
				.distinct()
				.filter { it != context.packageName }
				.map { AppEntry(it, labelOf(context, it)) }
				.sortedBy { it.label.lowercase() }
		}
	}

	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(stringResource(R.string.hidden_apps_add_title)) },
		text = {
			val list = apps
			if (list == null) {
				Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
					CircularProgressIndicator()
				}
			} else {
				LazyColumn(modifier = Modifier.fillMaxWidth()) {
					items(list.filter { it.packageName !in alreadyHidden }, key = { it.packageName }) { entry ->
						Row(
							modifier = Modifier
								.fillMaxWidth()
								.clickable { onPick(entry.packageName) }
								.padding(vertical = 8.dp),
							verticalAlignment = Alignment.CenterVertically,
						) {
							AppIcon(entry.packageName)
							Spacer(modifier = Modifier.size(16.dp))
							Text(entry.label, style = MaterialTheme.typography.bodyLarge)
						}
					}
				}
			}
		},
		confirmButton = {
			TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
		},
	)
}

@Composable
private fun AppIcon(packageName: String) {
	val context = LocalContext.current
	val bitmap = remember(packageName) {
		try {
			context.packageManager.getApplicationIcon(packageName).toBitmap(96, 96).asImageBitmap()
		} catch (ignored: Throwable) {
			null
		}
	}
	if (bitmap != null) {
		Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(40.dp))
	} else {
		Spacer(modifier = Modifier.size(40.dp))
	}
}

private fun labelOf(context: android.content.Context, packageName: String): String = try {
	val pm = context.packageManager
	pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
} catch (ignored: Throwable) {
	packageName
}
