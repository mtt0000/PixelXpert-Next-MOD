package sh.anirudhgupta109.pxn.util

import android.content.Context
import android.content.SharedPreferences
import com.topjohnwu.superuser.Shell
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import sh.anirudhgupta109.pxn.data.PrefStore

/**
 * Keeps a copy of every setting outside the app, so a lost install - an
 * uninstall, a signature change, a wipe of the app's data - does not take
 * the configuration with it.
 *
 * A few seconds after the last change, the whole preference file is written
 * as JSON (the [PrefStore.encodeJson] format) to two places through the root
 * shell: `/data/adb/pixelxpert`, which survives removing the app and the
 * module, and `Documents/PixelXpert` in shared storage, which the user can
 * see and copy off the phone. The previous copy is kept next to it as
 * `.prev`.
 *
 * Backups only start once the install has "seen" the backup question
 * ([SEEN_KEY]): a fresh install with an empty configuration must not
 * overwrite the copy that could bring the old one back. On the first start
 * without the key the app looks for a backup; with none there is nothing to
 * protect and backups start, with one the user is asked whether to restore
 * it ([find], [restore], [dismiss]).
 */
object AutoBackup {
	const val FORMAT = "pixelxpert-settings"
	const val SEEN_KEY = "AutoBackupSeen"

	private const val FILE_NAME = "settings_autobackup.json"
	private val DIRECTORIES = listOf("/data/adb/pixelxpert", "/sdcard/Documents/PixelXpert")
	private const val DELAY_SECONDS = 3L

	class Found(val values: Map<String, Any?>, val time: Long, val location: String)

	private val executor = Executors.newSingleThreadScheduledExecutor()
	private var pending: ScheduledFuture<*>? = null

	/** Held here because SharedPreferences only keeps its listeners weakly. */
	private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null

	/** Starts following preference changes; called once from `PxnApp.onCreate`. */
	fun start(context: Context) {
		val appContext = context.applicationContext
		val prefs = PrefStore.open(appContext)

		listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
			if (key != SEEN_KEY) schedule(appContext)
		}.also { prefs.registerOnSharedPreferenceChangeListener(it) }

		executor.execute {
			// Nothing to restore means nothing to protect: back up from now on.
			if (!prefs.getBoolean(SEEN_KEY, false) && readBackup() == null) {
				prefs.edit().putBoolean(SEEN_KEY, true).apply()
			}
			schedule(appContext)
		}
	}

	/** Whether the user still has to decide about a backup found at start. */
	fun needsDecision(context: Context): Boolean =
		!PrefStore.open(context.applicationContext).getBoolean(SEEN_KEY, false)

	/** The newest readable backup; blocks on the root shell. */
	fun find(): Found? = readBackup()

	/** Replaces the settings with [found] and resumes backing up. */
	fun restore(context: Context, store: PrefStore, found: Found): Boolean {
		val ok = store.replace({ it != PrefStore.LEGACY_INIT_MARKER }, found.values.filterKeys { it != PrefStore.LEGACY_INIT_MARKER })
		dismiss(context)
		return ok
	}

	/** Keeps the current settings; the next backup replaces the old copy. */
	fun dismiss(context: Context) {
		val prefs = PrefStore.open(context.applicationContext)
		prefs.edit().putBoolean(SEEN_KEY, true).apply()
		schedule(context.applicationContext)
	}

	private fun schedule(context: Context) {
		synchronized(this) {
			pending?.cancel(false)
			pending = executor.schedule({ write(context) }, DELAY_SECONDS, TimeUnit.SECONDS)
		}
	}

	private fun write(context: Context) {
		try {
			val prefs = PrefStore.open(context)
			if (!prefs.getBoolean(SEEN_KEY, false)) return

			val text = PrefStore.encodeJson(prefs.all, FORMAT) { it != PrefStore.LEGACY_INIT_MARKER }
			val temp = File(context.cacheDir, FILE_NAME)
			temp.writeText(text)

			val commands = DIRECTORIES.joinToString("; ") { dir ->
				"mkdir -p '$dir' && " +
					"{ [ -f '$dir/$FILE_NAME' ] && cp -f '$dir/$FILE_NAME' '$dir/$FILE_NAME.prev'; " +
					"cp -f '${temp.absolutePath}' '$dir/$FILE_NAME' && chmod 0644 '$dir/$FILE_NAME'; }"
			}
			Shell.cmd(commands).exec()
			temp.delete()
		} catch (ignored: Throwable) {
		}
	}

	/** The newest backup of the two places, or null. */
	private fun readBackup(): Found? {
		var best: Found? = null
		for (dir in DIRECTORIES) {
			try {
				val result = Shell.cmd("cat '$dir/$FILE_NAME'").exec()
				if (!result.isSuccess) continue
				val text = result.out.joinToString("\n")
				val values = PrefStore.decodeJson(text, FORMAT) { true } ?: continue
				if (values.isEmpty()) continue
				val time = JSONObject(text).optLong("time", 0L)
				if (best == null || time > best.time) best = Found(values, time, dir)
			} catch (ignored: Throwable) {
			}
		}
		return best
	}
}
