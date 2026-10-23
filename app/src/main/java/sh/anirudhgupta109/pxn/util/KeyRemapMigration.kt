package sh.anirudhgupta109.pxn.util

import android.content.Context
import sh.anirudhgupta109.pxn.data.PrefStore

/**
 * The physical key settings used to be six lists with their own codes
 * (ScreenOffKeys.PHYSICAL_ACTION_*); they are now gesture actions under new
 * keys. An old value is carried over once, when the new key is not set yet,
 * and the old key is removed. ScreenOffKeys reads the old keys the same way
 * while the app has not run this yet.
 */
object KeyRemapMigration {

	/** Old key to its replacement. */
	val KEYS: Map<String, String> = mapOf(
		"longPressPowerButtonScreenOn" to "KeyPowerLongOn",
		"longPressPowerButtonScreenOff" to "KeyPowerLongOff",
		"doublePressPowerButtonScreenOn" to "KeyPowerDoubleOn",
		"doublePressPowerButtonScreenOff" to "KeyPowerDoubleOff",
		"longPressVolumeUpButtonScreenOff" to "KeyVolUpLongOff",
		"longPressVolumeDownButtonScreenOff" to "KeyVolDownLongOff",
	)

	/**
	 * Old physical action code to gesture action code: none (-1) is now "do
	 * nothing" (0), default (0) is "-1", then torch, camera, assistant, play /
	 * pause, next and previous track.
	 */
	fun convert(old: String?): String? = when (old?.trim()) {
		"-1" -> "0"
		"1" -> "15"
		"2" -> "22"
		"3" -> "17"
		"5" -> "18"
		"6" -> "19"
		"7" -> "20"
		else -> null
	}

	fun migrate(context: Context) {
		try {
			val prefs = PrefStore.open(context)
			val editor = prefs.edit()
			var changed = false
			for ((old, new) in KEYS) {
				if (!prefs.contains(old)) continue
				if (!prefs.contains(new)) {
					convert(prefs.getString(old, null))?.let { editor.putString(new, it) }
				}
				editor.remove(old)
				changed = true
			}
			if (changed) editor.apply()
		} catch (ignored: Throwable) {
			// Locked (direct boot) or unreadable: the next start tries again.
		}
	}
}
