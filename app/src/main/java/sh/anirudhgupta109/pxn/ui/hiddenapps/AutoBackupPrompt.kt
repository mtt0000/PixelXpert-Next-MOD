package sh.anirudhgupta109.pxn.ui.hiddenapps

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.anirudhgupta109.pxn.R
import sh.anirudhgupta109.pxn.data.PrefStore
import sh.anirudhgupta109.pxn.util.AutoBackup
import sh.anirudhgupta109.pxn.util.Restarter

/**
 * Asks, once, whether to bring back the automatic backup when this install
 * has not decided about it yet and one exists - the case after the app's
 * data was lost. Restoring restarts SystemUI so the hooks read the settings
 * back; keeping the current ones lets the next backup replace the old copy.
 */
@Composable
fun AutoBackupPrompt(store: PrefStore) {
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	var found by remember { mutableStateOf<AutoBackup.Found?>(null) }

	LaunchedEffect(Unit) {
		if (!AutoBackup.needsDecision(context)) return@LaunchedEffect
		val backup = withContext(Dispatchers.IO) { AutoBackup.find() }
		if (backup == null) AutoBackup.dismiss(context) else found = backup
	}

	val backup = found ?: return
	val date = if (backup.time > 0) DateFormat.getDateTimeInstance().format(Date(backup.time)) else "?"

	AlertDialog(
		onDismissRequest = {},
		title = { Text(stringResource(R.string.autobackup_found_title)) },
		text = { Text(stringResource(R.string.autobackup_found_text, date, backup.values.size, backup.location)) },
		confirmButton = {
			TextButton(onClick = {
				found = null
				scope.launch(Dispatchers.IO) {
					if (AutoBackup.restore(context, store, backup)) Restarter.restartSystemUI()
				}
			}) { Text(stringResource(R.string.autobackup_restore)) }
		},
		dismissButton = {
			TextButton(onClick = {
				found = null
				AutoBackup.dismiss(context)
			}) { Text(stringResource(R.string.autobackup_keep)) }
		},
	)
}
