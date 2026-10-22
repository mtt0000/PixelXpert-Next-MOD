package sh.anirudhgupta109.pxn.util

import android.content.Context
import android.graphics.Color
import android.net.Uri
import java.util.zip.ZipInputStream
import org.json.JSONObject

/**
 * Turns a backup of the Edge Gestures app (`com.ss.edgegestures`) into this
 * module's edge gesture settings.
 *
 * The backup is a zip whose `prefs` entry is one JSON object. Its keys are an
 * edge letter (`l`, `r`, `b`) followed either by a gesture name (`lTap`,
 * `lLongPress`) or by the strokes of a swipe in screen directions - `lr` is
 * "right" on the left edge, `lrl` "right and back left", `lu45` "up
 * diagonally", `lrh` "right and hold". Each value is a JSON string: built-in
 * actions are `{"T":1,"i":index}`, shortcuts `{"T":2,"l":label,"u":uri}`
 * with the intent in `Intent.toUri` form.
 *
 * The indices follow the app's own action list (taken from its 2.0.6
 * build) and map onto the shared gesture action codes; the few without an
 * equivalent here - the pie control, "swap settings" - are reported back
 * rather than guessed.
 */
object EdgeGesturesImport {

	class Result(val values: Map<String, Any?>, val gestures: Int, val skipped: List<String>)

	/** Edge Gestures action index -> gesture action code; null = no equivalent. */
	private val ACTIONS: Map<Int, Int?> = mapOf(
		0 to 4, // Expand notifications panel
		1 to 13, // Expand quick settings
		2 to 10, // Home
		3 to 2, // Back
		4 to 11, // Recent apps
		5 to 14, // Power dialog
		6 to 28, // Disable for 5 seconds
		7 to 26, // Scroll to top
		8 to null, // Swap settings for 5s
		9 to 24, // Toggle split screen
		10 to 12, // Switch to the previous app
		11 to 29, // Disable until next touch
		12 to 31, // Voice command
		13 to 32, // Search
		14 to 1, // Screenshot
		15 to 16, // Toggle auto-rotate
		16 to 7, // Lock screen
		17 to 33, // Paste
		18 to 15, // Toggle flashlight
		19 to 38, // Ringtone volume
		20 to 39, // Notification volume
		21 to 40, // System volume
		22 to 21, // Media volume
		23 to null, // Open pie control
		24 to 30, // Screen blackout
		25 to 35, // Internet panel
		26 to 36, // WiFi panel
		27 to 21, // Volume panel
		28 to 37, // NFC panel
		29 to 34, // Current app info
		30 to 25, // Tap through
	)

	private val ACTION_NAMES = mapOf(8 to "Swap settings for 5s", 23 to "Open pie control")

	/** Pull and slide: Edge Gestures' "adjust" values -> this module's. */
	private val ADJUST: Map<Int, Int> = mapOf(
		0 to 0,
		1 to 1, // Brightness
		4 to 1, // Adaptive brightness
		2 to 2, // Media volume
		5 to 5, // System volume
		6 to 6, // Notification volume
		7 to 7, // Ringtone volume
	)

	private class Side(val letter: String, val prefix: String, val strokes: Map<String, String>)

	/**
	 * Strokes after the edge letter -> gesture key suffix. "In" is the
	 * direction away from the edge: right on the left edge, left on the
	 * right one, up on the bottom one; "up"/"down" on the bottom edge are
	 * left/right, as everywhere else in the module.
	 */
	private val SIDES = listOf(
		Side(
			"l", "EdgeLeft",
			mapOf(
				"r" to "SwipeIn", "rh" to "SwipeInHold", "rl" to "SwipeInOut",
				"u" to "SwipeUp", "uh" to "SwipeUpHold", "ud" to "SwipeUpDown",
				"d" to "SwipeDown", "dh" to "SwipeDownHold", "du" to "SwipeDownUp",
				"u45" to "SwipeDiagUp", "d45" to "SwipeDiagDown",
			),
		),
		Side(
			"r", "EdgeRight",
			mapOf(
				"l" to "SwipeIn", "lh" to "SwipeInHold", "lr" to "SwipeInOut",
				"u" to "SwipeUp", "uh" to "SwipeUpHold", "ud" to "SwipeUpDown",
				"d" to "SwipeDown", "dh" to "SwipeDownHold", "du" to "SwipeDownUp",
				"u45" to "SwipeDiagUp", "d45" to "SwipeDiagDown",
			),
		),
		Side(
			"b", "EdgeBottom",
			mapOf(
				"u" to "SwipeIn", "uh" to "SwipeInHold", "ud" to "SwipeInOut",
				"l" to "SwipeUp", "lh" to "SwipeUpHold", "lr" to "SwipeUpDown",
				"r" to "SwipeDown", "rh" to "SwipeDownHold", "rl" to "SwipeDownUp",
				"l45" to "SwipeDiagUp", "r45" to "SwipeDiagDown",
			),
		),
	)

	private val TAPS = mapOf("Tap" to "Tap", "DoubleTap" to "DoubleTap", "TripleTap" to "TripleTap", "LongPress" to "LongPress")

	/** Reads the backup at [uri]; null when it is not an Edge Gestures backup. */
	fun read(context: Context, uri: Uri): Result? {
		val json = try {
			context.contentResolver.openInputStream(uri)?.use { input ->
				ZipInputStream(input).use { zip ->
					var text: String? = null
					while (true) {
						val entry = zip.nextEntry ?: break
						if (entry.name == "prefs") {
							text = zip.readBytes().decodeToString()
							break
						}
					}
					text
				}
			}
		} catch (e: Exception) {
			null
		} ?: return null

		return try {
			convert(JSONObject(json))
		} catch (e: Exception) {
			null
		}
	}

	private fun convert(eg: JSONObject): Result {
		val out = LinkedHashMap<String, Any?>()
		val skipped = ArrayList<String>()
		var gestures = 0

		out["EdgeGesturesEnabled"] = true

		for (side in SIDES) {
			val p = side.prefix
			val l = side.letter
			val name = when (l) {
				"l" -> "enableLeft"
				"r" -> "enableRight"
				else -> "enableBottom"
			}
			if (eg.has(name)) out["${p}Enabled"] = eg.optBoolean(name)
			eg.optString("${l}Orientation").takeIf { it in setOf("1", "2", "3") }?.let { out["${p}Orientation"] = it }
			eg.optString("${l}Adjust").toIntOrNull()?.let { out["${p}Adjust"] = (ADJUST[it] ?: 0).toString() }

			// Sizes: the edge's own when it overrides the shared location.
			val own = eg.optBoolean("${l}OverrideLocation", false)
			if (l == "b") {
				val width = if (own) eg.optInt("bWidth", 60) else eg.optInt("width", 60)
				val offset = if (own) eg.optInt("bOffset", 0) else eg.optInt("offsetH", 0)
				out["${p}Length"] = width.coerceIn(10, 100).toFloat()
				out["${p}Position"] = (50 + offset).coerceIn(0, 100).toFloat()
				out["EdgeBottomOffset"] = eg.optInt("bottomOffset", 0).coerceIn(0, 200).toFloat()
			} else {
				val length = if (own) eg.optInt("${l}Length", 50) else eg.optInt("length", 50)
				val offset = if (own) eg.optInt("${l}Offset", 0) else eg.optInt("offset", 0)
				out["${p}Length"] = length.coerceIn(10, 100).toFloat()
				out["${p}Position"] = (50 + offset).coerceIn(0, 100).toFloat()
			}

			// Every gesture key of the edge is written, unset ones as "none",
			// so the import replaces the defaults instead of mixing with them.
			for (suffix in TAPS.values + side.strokes.values) out["$p$suffix"] = "-1"
			out["${p}LongSwipeIn"] = "-1"
			out["${p}LongSwipeDiagUp"] = "-1"
			out["${p}LongSwipeDiagDown"] = "-1"

			val keys = TAPS.mapKeys { l + it.key } + side.strokes.mapKeys { l + it.key }
			for ((egKey, suffix) in keys) {
				val raw = eg.optString(egKey).takeIf { it.isNotEmpty() } ?: continue
				val action = try {
					JSONObject(raw)
				} catch (e: Exception) {
					continue
				}
				val key = "$p$suffix"
				when (action.optInt("T")) {
					1 -> {
						val index = action.optInt("i", -1)
						val code = ACTIONS[index]
						if (code != null) {
							out[key] = code.toString()
							gestures++
						} else if (index >= 0) {
							skipped += "$egKey: " + (ACTION_NAMES[index] ?: "#$index")
						}
					}

					2 -> {
						val intentUri = action.optString("u")
						if (intentUri.isNotEmpty()) {
							out[key] = "intent:$intentUri"
							out[key + "Label"] = action.optString("l").ifEmpty { null }
							gestures++
						}
					}
				}
			}
		}

		eg.optInt("thickness", -1).takeIf { it > 0 }?.let { out["EdgeThickness"] = it.coerceIn(4, 48).toFloat() }
		eg.optInt("threshold", -1).takeIf { it > 0 }?.let { out["EdgeSwipeThreshold"] = it.coerceIn(10, 150).toFloat() }
		eg.optInt("holdTimeOut", -1).takeIf { it > 0 }?.let { out["EdgeHoldTimeout"] = it.coerceIn(200, 1500).toFloat() }
		if (eg.has("priorToSystemGesture")) out["EdgePriorityOverSystem"] = eg.optBoolean("priorToSystemGesture")
		if (eg.has("hapticFeedback")) out["EdgeHapticFeedback"] = eg.optBoolean("hapticFeedback")
		if (eg.has("soundEffect")) out["EdgeSoundEffect"] = eg.optBoolean("soundEffect")
		if (eg.has("disableOnHome")) out["EdgeDisableOnHome"] = eg.optBoolean("disableOnHome")
		eg.optString("behaviorOnVK").takeIf { it in setOf("1", "2", "3") }?.let { out["EdgeKeyboardBehavior"] = it }

		if (eg.has("bar")) out["EdgeShowHandle"] = eg.optBoolean("bar")
		if (eg.has("barColor")) {
			val color = eg.optInt("barColor")
			out["EdgeHandleColor"] = (color or 0xFF000000.toInt()).toString()
			out["EdgeHandleOpacity"] = (Color.alpha(color) * 100 / 255).coerceIn(5, 100).toFloat()
		}
		if (eg.has("visualEffect")) out["EdgeVisualEffect"] = eg.optBoolean("visualEffect")
		if (eg.has("effectColor")) out["EdgeEffectColor"] = eg.optInt("effectColor").toString()
		eg.optInt("waveHeight", -1).takeIf { it > 0 }?.let { out["EdgeWaveHeight"] = (it / 2).coerceIn(8, 80).toFloat() }
		eg.optInt("waveLength", -1).takeIf { it > 0 }?.let { out["EdgeWaveLength"] = it.coerceIn(60, 400).toFloat() }
		if (eg.has("showActionIcon")) out["EdgeShowActionLabel"] = eg.optBoolean("showActionIcon")

		return Result(out, gestures, skipped)
	}
}
