package sh.anirudhgupta109.pxn.ui.prefs

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import java.text.Normalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.anirudhgupta109.pxn.R
import sh.anirudhgupta109.pxn.data.PrefStore
import sh.anirudhgupta109.pxn.ui.hiddenapps.AppPickerDialog
import sh.anirudhgupta109.pxn.ui.hiddenapps.labelOf
import sh.anirudhgupta109.pxn.ui.prefs.node.ActionPref

const val ACTION_APP_PREFIX = "app:"
const val ACTION_INTENT_PREFIX = "intent:"

/** Key mode: Android's own behaviour, and swallowing the key. */
const val KEY_ACTION_DEFAULT = "-1"
const val KEY_ACTION_NOTHING = "0"

/**
 * Codes of SystemUIActions.EDGE_ACTIONS (tap through, scroll to top/bottom,
 * disable 5s / until touch, blackout, keep awake): they need the edge
 * gestures' windows, so a physical key cannot run them.
 */
private val EDGE_ONLY_ACTIONS = setOf("25", "26", "27", "28", "29", "30", "59")

/** The built-in actions as (code, label) pairs, in the list's order. */
private fun builtInActions(context: Context, keyMode: Boolean): List<Pair<String, String>> {
	val values = context.resources.getStringArray(R.array.edge_action_codes)
	val labels = context.resources.getStringArray(R.array.edge_action_titles)
	val all = values.indices.map { values[it] to labels.getOrElse(it) { values[it] } }
	if (!keyMode) return all
	return listOf(
		KEY_ACTION_DEFAULT to context.getString(R.string.key_action_default),
		KEY_ACTION_NOTHING to context.getString(R.string.key_action_nothing),
	) + all.filter { (code, _) -> code != KEY_ACTION_DEFAULT && code !in EDGE_ONLY_ACTIONS }
}

/** Where an action's shortcut name is kept, next to the action itself. */
fun actionLabelKey(key: String): String = key + "Label"

/**
 * How an action reads in a list: the built-in action's name, "Open <app>",
 * or the shortcut's own name.
 */
fun describeAction(context: Context, value: String?, shortcutLabel: String?, keyMode: Boolean = false): String {
	val v = value?.trim().orEmpty()
	return when {
		v.startsWith(ACTION_APP_PREFIX) ->
			context.getString(R.string.action_open_app, labelOf(context, v.removePrefix(ACTION_APP_PREFIX)))

		v.startsWith(ACTION_INTENT_PREFIX) ->
			shortcutLabel ?: context.getString(R.string.action_shortcut)

		else -> {
			val actions = builtInActions(context, keyMode)
			actions.firstOrNull { it.first == v.ifEmpty { "-1" } }?.second ?: actions[0].second
		}
	}
}

/**
 * A gesture action row. The dialog lists the built-in actions and two more
 * ways in: an app, picked from the launchable ones, or a shortcut, which
 * asks the chosen app's `CREATE_SHORTCUT` activity to build the intent -
 * the same way a home screen does, so Tasker tasks and the like work too.
 */
@Composable
internal fun ActionPrefItem(node: ActionPref, store: PrefStore) {
	val context = LocalContext.current
	// Keep `enabled` live across a write - see enabledBy.
	val revision by store.revision.collectAsState()
	@Suppress("UNUSED_VARIABLE") val ignored = revision
	val enabled = enabledBy(store, node)

	var open by remember { mutableStateOf(false) }
	var pickApp by remember { mutableStateOf(false) }
	var pickShortcut by remember { mutableStateOf(false) }

	val current = store.read(node) ?: node.default ?: "-1"
	val shown = describeAction(context, current, store.getString(actionLabelKey(node.key), null), node.keyMode)

	val shortcutLauncher = rememberLauncherForActivityResult(
		ActivityResultContracts.StartActivityForResult(),
	) { result ->
		val data = result.data ?: return@rememberLauncherForActivityResult
		val intent = data.getParcelableExtra(Intent.EXTRA_SHORTCUT_INTENT, Intent::class.java)
			?: return@rememberLauncherForActivityResult
		// The name first: the hooks read it when the action itself changes.
		store.putString(actionLabelKey(node.key), data.getStringExtra(Intent.EXTRA_SHORTCUT_NAME))
		store.write(node, ACTION_INTENT_PREFIX + intent.toUri(0))
	}

	ListItem(
		leadingContent = leadingIcon(node.icon),
		headlineContent = { Text(titleText(node)) },
		supportingContent = {
			Column {
				if (node.summary != 0) Text(stringResource(node.summary))
				Text(
					text = shown,
					color = MaterialTheme.colorScheme.primary,
					style = MaterialTheme.typography.bodySmall,
				)
			}
		},
		modifier = Modifier
			.fillMaxWidth()
			.dimUnless(enabled)
			.clickable(enabled = enabled) { open = true },
	)

	if (open) {
		val actions = remember(context, node.keyMode) { builtInActions(context, node.keyMode) }
		var query by remember { mutableStateOf("") }
		val appText = stringResource(R.string.action_pick_app)
		val shortcutText = stringResource(R.string.action_pick_shortcut)
		val matching = actions.filter { (_, label) -> matchesQuery(label, query) }

		AlertDialog(
			onDismissRequest = { open = false },
			title = { Text(titleText(node)) },
			text = {
				Column(modifier = Modifier.fillMaxWidth()) {
					OutlinedTextField(
						value = query,
						onValueChange = { query = it },
						singleLine = true,
						placeholder = { Text(stringResource(R.string.action_search_hint)) },
						leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
						trailingIcon = {
							if (query.isNotEmpty()) {
								IconButton(onClick = { query = "" }) {
									Icon(Icons.Default.Clear, contentDescription = stringResource(android.R.string.cancel))
								}
							}
						},
						modifier = Modifier
							.fillMaxWidth()
							.padding(bottom = 8.dp),
					)
					LazyColumn(modifier = Modifier.fillMaxWidth()) {
						if (matchesQuery(appText, query)) {
							item(key = "_app") {
								ChoiceRow(
									text = appText,
									selected = current.startsWith(ACTION_APP_PREFIX),
								) {
									open = false
									pickApp = true
								}
							}
						}
						if (matchesQuery(shortcutText, query)) {
							item(key = "_shortcut") {
								ChoiceRow(
									text = shortcutText,
									selected = current.startsWith(ACTION_INTENT_PREFIX),
								) {
									open = false
									pickShortcut = true
								}
								HorizontalDivider()
							}
						}
						items(matching, key = { it.first }) { (code, label) ->
							ChoiceRow(text = label, selected = code == current) {
								store.write(node, code)
								open = false
							}
						}
					}
				}
			},
			confirmButton = {
				TextButton(onClick = { open = false }) { Text(stringResource(android.R.string.cancel)) }
			},
		)
	}

	if (pickApp) {
		AppPickerDialog(
			title = R.string.action_pick_app,
			alreadyHidden = emptyList(),
			onPick = { pkg ->
				store.write(node, ACTION_APP_PREFIX + pkg)
				pickApp = false
			},
			onDismiss = { pickApp = false },
		)
	}

	if (pickShortcut) {
		ShortcutPickerDialog(
			onPick = { info ->
				pickShortcut = false
				try {
					shortcutLauncher.launch(
						Intent(Intent.ACTION_CREATE_SHORTCUT)
							.setComponent(ComponentName(info.activityInfo.packageName, info.activityInfo.name)),
					)
				} catch (ignored: Throwable) {
				}
			},
			onDismiss = { pickShortcut = false },
		)
	}
}

/**
 * Whether [text] contains every word of [query], ignoring case and accents,
 * so "cerca app" finds "Cerca nell'app".
 */
private fun matchesQuery(text: String, query: String): Boolean {
	val words = foldForSearch(query).split(' ').filter { it.isNotEmpty() }
	if (words.isEmpty()) return true
	val folded = foldForSearch(text)
	return words.all { folded.contains(it) }
}

private fun foldForSearch(text: String): String =
	Normalizer.normalize(text, Normalizer.Form.NFD)
		.replace(Regex("\\p{Mn}+"), "")
		.lowercase()

@Composable
private fun ChoiceRow(text: String, selected: Boolean, onClick: () -> Unit) {
	Row(
		verticalAlignment = Alignment.CenterVertically,
		modifier = Modifier
			.fillMaxWidth()
			.clickable(onClick = onClick)
			.padding(vertical = 4.dp),
	) {
		RadioButton(selected = selected, onClick = null)
		Text(text = text, modifier = Modifier.padding(start = 12.dp))
	}
}

/** The activities that build home screen shortcuts, as a launcher lists them. */
@Composable
private fun ShortcutPickerDialog(onPick: (ResolveInfo) -> Unit, onDismiss: () -> Unit) {
	val context = LocalContext.current

	val shortcuts by produceState<List<Pair<ResolveInfo, String>>?>(initialValue = null) {
		value = withContext(Dispatchers.Default) {
			val pm = context.packageManager
			pm.queryIntentActivities(Intent(Intent.ACTION_CREATE_SHORTCUT), 0)
				.map { it to it.loadLabel(pm).toString() }
				.sortedBy { it.second.lowercase() }
		}
	}

	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(stringResource(R.string.action_pick_shortcut)) },
		text = {
			val list = shortcuts
			if (list == null) {
				Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
					CircularProgressIndicator()
				}
			} else {
				LazyColumn(modifier = Modifier.fillMaxWidth()) {
					items(list, key = { it.first.activityInfo.packageName + "/" + it.first.activityInfo.name }) { (info, label) ->
						Row(
							modifier = Modifier
								.fillMaxWidth()
								.clickable { onPick(info) }
								.padding(vertical = 8.dp),
							verticalAlignment = Alignment.CenterVertically,
						) {
							val icon = remember(info) {
								try {
									info.loadIcon(context.packageManager).toBitmap(96, 96).asImageBitmap()
								} catch (ignored: Throwable) {
									null
								}
							}
							if (icon != null) Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(40.dp))
							else Spacer(modifier = Modifier.size(40.dp))
							Spacer(modifier = Modifier.size(16.dp))
							Text(label, style = MaterialTheme.typography.bodyLarge)
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
