package sh.anirudhgupta109.pxn.ui.hiddenapps

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.koin.compose.koinInject
import sh.anirudhgupta109.pxn.R
import sh.anirudhgupta109.pxn.data.PrefStore
import sh.anirudhgupta109.pxn.util.EdgeGesturesImport

/** The format tag of a gesture settings backup. */
private const val GESTURE_BACKUP_FORMAT = "pixelxpert-gestures"

/** The gesture navigation keys that are not named after the edge gestures. */
private val GESTURE_NAV_KEYS = setOf(
	"BackFromLeft", "BackFromRight", "BackLeftHeight", "BackRightHeight",
	"FCLongSwipeEnabled",
	"leftSwipeUpAction", "leftSwipeUpActionLabel", "leftSwipeUpPercentage",
	"rightSwipeUpAction", "rightSwipeUpActionLabel", "rightSwipeUpPercentage",
	"twoFingerSwipeUpAction", "twoFingerSwipeUpActionLabel", "swipeUpPercentage",
	"navPillColorAccent", "GesPillWidthModPos", "GesPillHeightFactor",
	"KeyboardSpaceHideButtons", "HideNavbarInsets",
	"QSPullodwnEnabled", "QSPulldownPercent", "QSPulldownSide", "oneFingerPullupEnabled",
	"DoubleTapSleep", "StatusbarLongpressAppSwitch",
	"DoubleTapSleepLockscreen", "holdScreenTorchEnabled",
	"doubleTapToWake", "TapToShowAmbient", "PickToShowAmbient",
)

/** Whether [key] is one of the settings a gesture backup carries. */
fun isGestureSetting(key: String): Boolean =
	key.startsWith("Edge") || key in GESTURE_NAV_KEYS

/**
 * Edge Gestures' own backup only covers the edge gestures - and has no list
 * of excluded apps, so the one already set up here is kept.
 */
private fun isEdgeSetting(key: String): Boolean = key.startsWith("Edge") && key != "EdgeExcludedApps"

/**
 * Settings > Navigation bar > Gestures > Backup & restore.
 *
 * Saves and restores every gesture setting - the edge gestures, the back
 * gesture, the bottom swipes and the pill - as one readable JSON file, and
 * imports a backup made by the Edge Gestures app, which replaces the edge
 * gesture settings. The app-wide backup in the top bar menu is unaffected:
 * this one touches the gesture keys only.
 */
@Composable
fun GestureBackupScreen() {
	val context = LocalContext.current
	val store: PrefStore = koinInject()
	var message by remember { mutableStateOf<String?>(null) }

	val exportLauncher = rememberLauncherForActivityResult(
		ActivityResultContracts.CreateDocument("application/json"),
	) { uri ->
		if (uri != null) {
			val ok = store.exportJson(uri, GESTURE_BACKUP_FORMAT, ::isGestureSetting)
			message = context.getString(if (ok) R.string.gesture_backup_done else R.string.gesture_backup_failed)
		}
	}

	val importLauncher = rememberLauncherForActivityResult(
		ActivityResultContracts.OpenDocument(),
	) { uri ->
		if (uri != null) {
			val count = store.importJson(uri, GESTURE_BACKUP_FORMAT, ::isGestureSetting)
			message =
				if (count >= 0) context.getString(R.string.gesture_restore_done, count)
				else context.getString(R.string.gesture_restore_failed)
		}
	}

	val edgeGesturesLauncher = rememberLauncherForActivityResult(
		ActivityResultContracts.OpenDocument(),
	) { uri ->
		if (uri != null) {
			val result = EdgeGesturesImport.read(context, uri)
			message = if (result == null) {
				context.getString(R.string.gesture_import_eg_failed)
			} else {
				store.replace(::isEdgeSetting, result.values)
				buildString {
					append(context.getString(R.string.gesture_import_eg_done, result.gestures))
					if (result.skipped.isNotEmpty()) {
						append("\n\n")
						append(context.getString(R.string.gesture_import_eg_skipped))
						result.skipped.forEach { append("\n• ").append(it) }
					}
				}
			}
		}
	}

	Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
		ListItem(
			headlineContent = { Text(stringResource(R.string.gesture_backup_export_title)) },
			supportingContent = { Text(stringResource(R.string.gesture_backup_export_summary)) },
			modifier = Modifier.fillMaxWidth().clickable {
				val stamp = SimpleDateFormat("yyMMdd", Locale.US).format(Date())
				exportLauncher.launch("PixelXpert_gestures_$stamp.json")
			},
		)
		ListItem(
			headlineContent = { Text(stringResource(R.string.gesture_backup_import_title)) },
			supportingContent = { Text(stringResource(R.string.gesture_backup_import_summary)) },
			modifier = Modifier.fillMaxWidth().clickable {
				importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
			},
		)
		ListItem(
			headlineContent = { Text(stringResource(R.string.gesture_import_eg_title)) },
			supportingContent = { Text(stringResource(R.string.gesture_import_eg_summary)) },
			modifier = Modifier.fillMaxWidth().clickable {
				edgeGesturesLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
			},
		)
	}

	message?.let { text ->
		AlertDialog(
			onDismissRequest = { message = null },
			text = { Text(text) },
			confirmButton = {
				TextButton(onClick = { message = null }) { Text(stringResource(android.R.string.ok)) }
			},
		)
	}
}
