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
		// Some senders put the stream only in the intent's ClipData.
		val clip = intent.clipData
		if (clip != null) {
			for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { out.add(it) }
		}
		return out.distinct()
	}

	private fun handleSend(intent: Intent) {
		val builder = StringBuilder()

		val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
		if (text != null) builder.append(text)

		val uris = streamUris(intent)
		for (uri in uris) {
			val content = readTextContent(uri, intent.type)
			if (!content.isNullOrEmpty()) {
				if (builder.isNotEmpty()) builder.append("\n")
				builder.append(content)
			}
		}
		copyToClipboard(builder.toString(), copyImages(uris, intent.type))
	}

	private fun handleSendMultiple(intent: Intent) {
		val parts = ArrayList<String>()

		val uris = streamUris(intent)
		for (uri in uris) {
			val content = readTextContent(uri, intent.type)
			if (!content.isNullOrEmpty()) parts.add(content)
		}
		if (parts.isEmpty()) {
			val texts = intent.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT)
			texts?.forEach { if (it != null) parts.add(it.toString()) }
		}
		copyToClipboard(parts.joinToString("\n\n---\n\n"), copyImages(uris, intent.type))
	}

	/**
	 * Copies the shared images into [CLIP_DIR] and returns their FileProvider
	 * URIs. The previous copies are dropped first: the clipboard holds a single
	 * clip, so they are no longer reachable anyway.
	 */
	private fun copyImages(uris: List<Uri>, intentType: String?): List<Uri> {
		val images = uris.mapNotNull { uri -> imageType(uri, intentType)?.let { uri to it } }
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

	/** The provider's type, unless it is missing or a catch-all, then the one the intent declares. */
	private fun declaredType(uri: Uri, intentType: String?): String? {
		val resolved = try {
			contentResolver.getType(uri)
		} catch (ignored: Throwable) {
			null
		}
		return resolved?.takeUnless { it in GENERIC_TYPES } ?: intentType?.takeUnless { it in GENERIC_TYPES }
	}

	/**
	 * The image type of [uri], or null when it is not an image. The file's
	 * first bytes win over what is declared: WhatsApp's provider, for one,
	 * does not report a type for the media it shares.
	 */
	private fun imageType(uri: Uri, intentType: String?): String? {
		sniffImageType(uri)?.let { return it }
		val declared = declaredType(uri, intentType) ?: return null
		return when {
			!declared.startsWith("image/") -> null
			declared == "image/*" -> "image/jpeg"
			else -> declared
		}
	}

	private fun sniffImageType(uri: Uri): String? {
		val head = try {
			contentResolver.openInputStream(uri)?.use { it.readNBytes(16) }
		} catch (ignored: Throwable) {
			null
		} ?: return null
		fun at(offset: Int, vararg bytes: Int) =
			head.size >= offset + bytes.size && bytes.indices.all { head[offset + it] == bytes[it].toByte() }
		return when {
			at(0, 0xFF, 0xD8, 0xFF) -> "image/jpeg"
			at(0, 0x89, 0x50, 0x4E, 0x47) -> "image/png"
			at(0, 0x47, 0x49, 0x46, 0x38) -> "image/gif"
			at(0, 0x52, 0x49, 0x46, 0x46) && at(8, 0x57, 0x45, 0x42, 0x50) -> "image/webp"
			at(4, 0x66, 0x74, 0x79, 0x70) && (at(8, 0x68, 0x65, 0x69, 0x63) || at(8, 0x6D, 0x69, 0x66, 0x31)) -> "image/heic"
			at(0, 0x42, 0x4D) -> "image/bmp"
			else -> null
		}
	}

	/** Text of [uri], or null when it is not text (images, videos, any binary data) or unreadable. */
	private fun readTextContent(uri: Uri, intentType: String?): String? {
		return try {
			if (sniffImageType(uri) != null) return null
			val type = declaredType(uri, intentType)
			val isText = type == null ||
				type.startsWith("text/") ||
				type == "application/json" ||
				type == "application/xml" ||
				type == "application/javascript"
			if (!isText) return null

			contentResolver.openInputStream(uri)?.use { stream ->
				val bytes = stream.readNBytes(MAX_BYTES)
				// A NUL byte means binary data with no declared type, not text.
				if (bytes.any { it == 0.toByte() }) null else String(bytes, StandardCharsets.UTF_8)
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
		val GENERIC_TYPES = setOf("*/*", "application/octet-stream")
	}
}
