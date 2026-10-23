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
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * Share-sheet target "Copy content in clipboard".
 *
 * Receives ACTION_SEND / ACTION_SEND_MULTIPLE, puts the shared text (and the
 * text of shared text files) on the clipboard and closes immediately.
 * Every other shared file - images above all - is copied into our cache and
 * put on the clipboard as a FileProvider URI: the read grant we got from the
 * sender ends with this activity, so the original URI would be unreadable for
 * whoever pastes. Images are recognised by their first bytes, since senders
 * such as WhatsApp do not always declare a type.
 * The component is switched on/off by the `ClipboardShareEnabled` preference
 * (see syncClipboardShareComponent in PrefActions.kt).
 */
class ClipboardShareActivity : Activity() {

	/** The first failure while reading a shared file, for the toast. */
	private var readError: Throwable? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		// No check on intent.type: a sender may share a stream without one.
		when (intent?.action) {
			Intent.ACTION_SEND -> handleSend(intent)
			Intent.ACTION_SEND_MULTIPLE -> handleSendMultiple(intent)
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

		val files = ArrayList<Uri>()
		for (uri in streamUris(intent)) {
			val content = readTextContent(uri, intent.type)
			if (content == null) {
				files.add(uri)
			} else if (content.isNotEmpty()) {
				if (builder.isNotEmpty()) builder.append("\n")
				builder.append(content)
			}
		}
		copyToClipboard(builder.toString(), copyFiles(files, intent.type))
	}

	private fun handleSendMultiple(intent: Intent) {
		val parts = ArrayList<String>()

		val files = ArrayList<Uri>()
		for (uri in streamUris(intent)) {
			val content = readTextContent(uri, intent.type)
			if (content == null) files.add(uri) else if (content.isNotEmpty()) parts.add(content)
		}
		if (parts.isEmpty()) {
			val texts = intent.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT)
			texts?.forEach { if (it != null) parts.add(it.toString()) }
		}
		copyToClipboard(parts.joinToString("\n\n---\n\n"), copyFiles(files, intent.type))
	}

	/**
	 * Copies the shared files into [CLIP_DIR] and returns their FileProvider
	 * URIs, images first. The previous copies are dropped first: the
	 * clipboard holds a single clip, so they are no longer reachable anyway.
	 */
	private fun copyFiles(uris: List<Uri>, intentType: String?): List<Uri> {
		if (uris.isEmpty()) return emptyList()

		val dir = File(cacheDir, CLIP_DIR)
		dir.deleteRecursively()
		dir.mkdirs()

		val copies = ArrayList<Pair<Uri, Boolean>>()
		uris.forEachIndexed { index, uri ->
			try {
				val image = imageType(uri, intentType)
				val type = image ?: declaredType(uri, intentType)
				val ext = type?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) } ?: "bin"
				val file = File(dir, "shared_${System.currentTimeMillis()}_$index.$ext")
				val copied = open(uri)?.use { input ->
					file.outputStream().use { output -> input.copyTo(output) }
				}
				if (copied != null && copied > 0) {
					copies.add(FileProvider.getUriForFile(this, "$packageName.fileprovider", file) to (image != null))
				} else {
					file.delete()
				}
			} catch (e: Exception) {
				Log.e(TAG, "Error copying $uri", e)
				if (readError == null) readError = e
			}
		}
		return copies.sortedByDescending { it.second }.map { it.first }
	}

	/** The content of [uri]; when a plain open fails, through its asset file descriptor. */
	private fun open(uri: Uri): InputStream? = try {
		contentResolver.openInputStream(uri)
	} catch (e: Exception) {
		if (readError == null) readError = e
		contentResolver.openAssetFileDescriptor(uri, "r")?.createInputStream()
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
			open(uri)?.use { it.readNBytes(16) }
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

	/**
	 * Text of [uri]; "" when it is text but unreadable, null when it is not
	 * text (images, videos, any binary data), so it gets copied as a file.
	 */
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

			val bytes = open(uri)?.use { it.readNBytes(MAX_BYTES) } ?: return ""
			// A NUL byte means binary data with no declared type, not text.
			if (bytes.any { it == 0.toByte() }) null else String(bytes, StandardCharsets.UTF_8)
		} catch (e: Exception) {
			Log.e(TAG, "Error reading $uri", e)
			if (readError == null) readError = e
			""
		}
	}

	/** Files go first, so apps that only read the first clip item paste the image. */
	private fun copyToClipboard(content: String, files: List<Uri> = emptyList()) {
		if (content.isEmpty() && files.isEmpty()) {
			val error = readError
			if (error != null) {
				toast(getString(R.string.clipboard_share_read_failed, error.javaClass.simpleName))
			} else {
				toast(getString(R.string.clipboard_share_nothing))
			}
			return
		}

		val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
		if (clipboard != null) {
			val clip = if (files.isEmpty()) {
				ClipData.newPlainText("Copied Content", content)
			} else {
				ClipData.newUri(contentResolver, "Copied Content", files[0]).apply {
					for (i in 1 until files.size) addItem(contentResolver, ClipData.Item(files[i]))
					if (content.isNotEmpty()) addItem(contentResolver, ClipData.Item(content))
				}
			}
			clipboard.setPrimaryClip(clip)
			val image = files.isNotEmpty() && (contentResolver.getType(files[0])?.startsWith("image/") == true)
			toast(
				getString(
					when {
						files.isEmpty() -> R.string.clipboard_share_done
						image -> R.string.clipboard_share_image_done
						else -> R.string.clipboard_share_file_done
					},
				),
			)
		} else {
			toast(getString(R.string.clipboard_share_failed))
		}
	}

	private fun toast(text: String) {
		Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()
	}

	private companion object {
		const val TAG = "ClipboardShareActivity"
		const val MAX_BYTES = 1024 * 1024
		const val CLIP_DIR = "clipboard"
		val GENERIC_TYPES = setOf("*/*", "application/octet-stream")
	}
}
