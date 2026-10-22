package sh.anirudhgupta109.pxn

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Share-sheet target "Copy content in clipboard".
 *
 * Receives ACTION_SEND / ACTION_SEND_MULTIPLE, puts the shared text (and the
 * text of shared text files) on the clipboard and closes immediately.
 * Shared images are copied into our cache and put on the clipboard as
 * FileProvider URIs: the read grant we got from the sender ends with this
 * activity, so their original URIs would be unreadable for whoever pastes.
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

	private fun streamUris(intent: Intent): List<Uri> {
		val out = ArrayList<Uri>()
		try {
			val single = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
			if (single != null) out.add(single)
		} catch (ignored: Throwable) {
		}
		try {
			val many = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
			if (many != null) out.addAll(many.filterNotNull())
		} catch (ignored: Throwable) {
		}
		return out.distinct()
	}

	private fun handleSend(intent: Intent) {
		val builder = StringBuilder()

		val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
		if (text != null) builder.append(text)

		val uris = streamUris(intent)
		for (uri in uris) {
			val content = readTextContent(uri)
			if (!content.isNullOrEmpty()) {
				if (builder.isNotEmpty()) builder.append("\n")
				builder.append(content)
			}
		}
		copyToClipboard(builder.toString(), copyImages(uris))
	}

	private fun handleSendMultiple(intent: Intent) {
		val parts = ArrayList<String>()

		val uris = streamUris(intent)
		for (uri in uris) {
			val content = readTextContent(uri)
			if (!content.isNullOrEmpty()) parts.add(content)
		}
		if (parts.isEmpty()) {
			val texts = intent.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT)
			texts?.forEach { if (it != null) parts.add(it.toString()) }
		}
		copyToClipboard(parts.joinToString("\n\n---\n\n"), copyImages(uris))
	}

	/**
	 * Copies the shared images into [CLIP_DIR] and returns their FileProvider
	 * URIs. The previous copies are dropped first: the clipboard holds a single
	 * clip, so they are no longer reachable anyway.
	 */
	private fun copyImages(uris: List<Uri>): List<Uri> {
		val images = uris.mapNotNull { uri ->
			val type = try {
				contentResolver.getType(uri)
			} catch (ignored: Throwable) {
				null
			}
			if (type != null && type.startsWith("image/")) uri to type else null
		}
		if (images.isEmpty()) return emptyList()

		val dir = File(cacheDir, CLIP_DIR)
		dir.deleteRecursively()
		dir.mkdirs()

		val out = ArrayList<Uri>()
		images.forEachIndexed { index, (uri, type) ->
			try {
				val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(type) ?: "png"
				val file = File(dir, "image_${System.currentTimeMillis()}_$index.$ext")
				val copied = contentResolver.openInputStream(uri)?.use { input ->
					file.outputStream().use { output -> input.copyTo(output) }
				}
				if (copied != null && copied > 0) {
					out.add(FileProvider.getUriForFile(this, "$packageName.fileprovider", file))
				} else {
					file.delete()
				}
			} catch (e: Exception) {
				Log.e(TAG, "Error copying image $uri", e)
			}
		}
		return out
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

	/** Images go first, so apps that only read the first clip item paste the image. */
	private fun copyToClipboard(content: String, images: List<Uri> = emptyList()) {
		if (content.isEmpty() && images.isEmpty()) {
			Toast.makeText(applicationContext, R.string.clipboard_share_nothing, Toast.LENGTH_SHORT).show()
			return
		}

		val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
		if (clipboard != null) {
			val clip = if (images.isEmpty()) {
				ClipData.newPlainText("Copied Content", content)
			} else {
				ClipData.newUri(contentResolver, "Copied Content", images[0]).apply {
					for (i in 1 until images.size) addItem(contentResolver, ClipData.Item(images[i]))
					if (content.isNotEmpty()) addItem(contentResolver, ClipData.Item(content))
				}
			}
			clipboard.setPrimaryClip(clip)
			Toast.makeText(
				applicationContext,
				if (images.isEmpty()) R.string.clipboard_share_done else R.string.clipboard_share_image_done,
				Toast.LENGTH_SHORT,
			).show()
		} else {
			Toast.makeText(applicationContext, R.string.clipboard_share_failed, Toast.LENGTH_SHORT).show()
		}
	}

	private companion object {
		const val TAG = "ClipboardShareActivity"
		const val MAX_BYTES = 1024 * 1024
		const val CLIP_DIR = "clipboard"
	}
}
