package sh.anirudhgupta109.pxn

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import java.nio.charset.StandardCharsets

/**
 * Share-sheet target "Copy content in clipboard".
 *
 * Receives ACTION_SEND / ACTION_SEND_MULTIPLE, puts the shared text (and the
 * text of shared text files) on the clipboard and closes immediately.
 * The component is switched on/off by the `ClipboardShareEnabled` preference
 * (see syncClipboardShareComponent in PrefActions.kt).
 */
class ClipboardShareActivity : Activity() {

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		val action = intent?.action
		if (intent != null && intent.type != null) {
			when (action) {
				Intent.ACTION_SEND -> handleSend(intent)
				Intent.ACTION_SEND_MULTIPLE -> handleSendMultiple(intent)
			}
		}

		finish()
	}

	@Suppress("DEPRECATION")
	private fun streamUris(intent: Intent): List<Uri> {
		val out = ArrayList<Uri>()
		try {
			val single = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
			if (single != null) out.add(single)
		} catch (ignored: Throwable) {
		}
		try {
			val many = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
			if (many != null) out.addAll(many.filterNotNull())
		} catch (ignored: Throwable) {
		}
		return out.distinct()
	}

	private fun handleSend(intent: Intent) {
		val builder = StringBuilder()

		val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
		if (text != null) builder.append(text)

		for (uri in streamUris(intent)) {
			val content = readTextContent(uri)
			if (!content.isNullOrEmpty()) {
				if (builder.isNotEmpty()) builder.append("\n")
				builder.append(content)
			}
		}
		copyToClipboard(builder.toString())
	}

	private fun handleSendMultiple(intent: Intent) {
		val parts = ArrayList<String>()

		for (uri in streamUris(intent)) {
			val content = readTextContent(uri)
			if (!content.isNullOrEmpty()) parts.add(content)
		}
		if (parts.isEmpty()) {
			val texts = intent.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT)
			texts?.forEach { if (it != null) parts.add(it.toString()) }
		}
		copyToClipboard(parts.joinToString("\n\n---\n\n"))
	}

	/** Text of [uri], or null when it is clearly not text (images, videos...) or unreadable. */
	private fun readTextContent(uri: Uri): String? {
		return try {
			val type = contentResolver.getType(uri)
			val isText = type == null ||
				type.startsWith("text/") ||
				type == "application/json" ||
				type == "application/xml" ||
				type == "application/javascript"
			if (!isText) return null

			contentResolver.openInputStream(uri)?.use { stream ->
				val bytes = stream.readNBytes(MAX_BYTES)
				String(bytes, StandardCharsets.UTF_8)
			}
		} catch (e: Exception) {
			Log.e(TAG, "Error reading $uri", e)
			null
		}
	}

	private fun copyToClipboard(content: String) {
		if (content.isEmpty()) {
			Toast.makeText(this, R.string.clipboard_share_nothing, Toast.LENGTH_SHORT).show()
			return
		}

		val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
		if (clipboard != null) {
			clipboard.setPrimaryClip(ClipData.newPlainText("Copied Content", content))
			Toast.makeText(this, R.string.clipboard_share_done, Toast.LENGTH_SHORT).show()
		} else {
			Toast.makeText(this, R.string.clipboard_share_failed, Toast.LENGTH_SHORT).show()
		}
	}

	private companion object {
		const val TAG = "ClipboardShareActivity"
		const val MAX_BYTES = 1024 * 1024
	}
}
