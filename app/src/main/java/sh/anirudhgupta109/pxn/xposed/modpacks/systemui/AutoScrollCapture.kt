package sh.anirudhgupta109.pxn.xposed.modpacks.systemui

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.view.Choreographer
import android.view.Gravity
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.res.ResourcesCompat
import de.robv.android.xposed.XposedHelpers.callMethod
import java.lang.reflect.Proxy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import sh.anirudhgupta109.pxn.R
import sh.anirudhgupta109.pxn.xposed.XPLauncher
import sh.anirudhgupta109.pxn.xposed.utils.toolkit.Logger

/**
 * Samsung-style "scroll capture" for apps the platform long screenshot does
 * not support: instead of asking the app to render itself through
 * ScrollCaptureCallback, it swipes the screen up by itself, captures the
 * display after every swipe and stitches the frames together.
 *
 * While it runs a small floating bar is shown. In [manual] mode (Samsung's
 * behaviour) every tap on "Scroll" adds one page and "Done" saves; otherwise
 * it keeps scrolling by itself and "Stop" saves what was captured so far. A
 * tap anywhere else on the screen also ends it: an input monitor - the kind
 * the screenshot shelf itself uses - sees the touch and pilfers it, so the
 * app underneath does not get the tap. The bar is hidden while a step swipes
 * and captures, so it never ends up in the stitched image.
 *
 * Stitching works on per-row hashes, see [Stitcher]. The capture also stops
 * at the end of the page (nothing moved), at [maxSteps] scrolls in automatic
 * mode, at [MAX_HEIGHT] or when no overlap can be found.
 *
 * The capture loop runs on its own thread; the bar, the input monitor and
 * the toasts live on the main thread. [imageCapture] is SystemUI's
 * `ImageCaptureImpl`.
 */
class AutoScrollCapture(
	private val context: Context,
	private val imageCapture: Any,
	private val displayId: Int,
	private val maxSteps: Int,
	private val manual: Boolean,
) {
	private enum class Command { STEP, FINISH }

	private val mainHandler = Handler(Looper.getMainLooper())
	private val commands = LinkedBlockingQueue<Command>()

	/** True while our own swipe is injected, so the tap monitor ignores it. */
	@Volatile
	private var injecting = false

	private var bar: View? = null
	private var windowManager: WindowManager? = null
	private var inputMonitor: Any? = null
	private var inputReceiver: Any? = null

	/**
	 * `captureDisplay(displayId, crop)` in AOSP; looked up by name so an added
	 * parameter does not break it - the int is the display, the rest is null
	 * / false (no crop, defaults).
	 */
	private val captureDisplay = imageCapture.javaClass.methods.first {
		it.name == "captureDisplay" && it.parameterTypes.firstOrNull() == Int::class.javaPrimitiveType
	}
	private val captureArgs: Array<Any?> = captureDisplay.parameterTypes.mapIndexed { index, type ->
		when {
			index == 0 -> displayId
			type == Boolean::class.javaPrimitiveType -> false
			type == Int::class.javaPrimitiveType -> 0
			else -> null
		}
	}.toTypedArray()

	fun start() {
		Thread({
			try {
				run()
			} catch (t: Throwable) {
				Logger.log(t)
				toast(R.string.scroll_capture_failed)
			} finally {
				runOnMainSync { tearDownUi() }
				running = false
			}
		}, "PXN-ScrollCapture").start()
	}

	private fun run() {
		// No "started" toast: it would be in the first frame. Settled, so the
		// fading screenshot shelf is not part of it either.
		val (first, firstHashes) = captureSettled() ?: return toast(R.string.scroll_capture_failed)
		val height = first.height
		val swipeFrom = height * 0.72f
		val swipeTo = height * 0.28f
		val stitcher = Stitcher(first, firstHashes, (swipeFrom - swipeTo).toInt(), statusBarHeight())

		runOnMainSync {
			showBar()
			startTapMonitor()
		}

		val power = context.getSystemService(PowerManager::class.java)
		var steps = 0
		while (true) {
			val command = if (manual) {
				commands.poll(MANUAL_TIMEOUT, TimeUnit.SECONDS) ?: Command.FINISH
			} else {
				commands.poll(AUTO_PAUSE, TimeUnit.MILLISECONDS) ?: Command.STEP
			}
			if (command == Command.FINISH) break
			if (!manual && steps >= maxSteps) break
			if (power != null && !power.isInteractive) break

			setBarVisible(false)
			swipe(first.width / 2f, swipeFrom, swipeTo)
			val (cur, hashes) = captureSettled() ?: break
			steps++
			if (!stitcher.add(cur, hashes)) break
			setBarVisible(true)
		}

		runOnMainSync { tearDownUi() }

		val result = stitcher.build() ?: return toast(R.string.scroll_capture_nothing)
		val uri = save(result)
		result.recycle()
		if (uri == null) return toast(R.string.scroll_capture_failed)

		toast(R.string.scroll_capture_saved)
		mainHandler.post { openResult(uri) }
	}

	/**
	 * Holds the first frame, the strips every scroll revealed and the last
	 * frame. The rows that do not move between the first two frames (status
	 * bar, toolbar, bottom bar / navigation bar) are taken as fixed header and
	 * footer; for every following frame the vertical offset is the shift at
	 * which the scrolling area of the new frame overlaps the previous one,
	 * preferring the shift closest to the swipe length when several fit (blank
	 * or repeating content).
	 */
	private inner class Stitcher(
		private val first: Bitmap,
		private var prevHashes: LongArray,
		private val expectedShift: Int,
		private val statusBar: Int,
	) {
		private val width = first.width
		private val height = first.height
		private var top = -1
		private var bottom = -1
		private var totalHeight = height
		private var last: Bitmap = first
		private val strips = ArrayList<Bitmap>()

		/** Takes ownership of [cur]; false when the capture should stop. */
		fun add(cur: Bitmap, hashes: LongArray): Boolean {
			if (top < 0) {
				// The first swipe tells the fixed bars apart from the scrolling area.
				// If nothing moved, findShift returns 0 below and strips stays empty.
				top = fixedTop(prevHashes, hashes, statusBar)
				bottom = fixedBottom(prevHashes, hashes, top)
				if (height - top - bottom < MIN_REGION) {
					cur.recycle()
					return false
				}
			}

			val regionHeight = height - top - bottom
			val shift = findShift(prevHashes, hashes, top, regionHeight, expectedShift)
			if (shift <= 0) {
				// End of the page (0) or no overlap found (-1): keep what we have.
				cur.recycle()
				return false
			}

			val add = minOf(shift, MAX_HEIGHT - totalHeight)
			if (add <= 0) {
				cur.recycle()
				return false
			}
			strips.add(Bitmap.createBitmap(cur, 0, top + regionHeight - add, width, add))
			totalHeight += add

			if (last !== first) last.recycle()
			last = cur
			prevHashes = hashes
			return add == shift
		}

		/** The stitched image, or null when nothing scrolled; recycles every frame. */
		fun build(): Bitmap? {
			if (strips.isEmpty()) {
				recycleFrames()
				return null
			}
			val result = Bitmap.createBitmap(width, totalHeight, Bitmap.Config.ARGB_8888)
			val canvas = Canvas(result)
			// Header and the whole scrolling area from the first frame...
			canvas.drawBitmap(first, Rect(0, 0, width, height - bottom), Rect(0, 0, width, height - bottom), null)
			// ...then every newly revealed strip...
			var y = height - bottom
			for (strip in strips) {
				canvas.drawBitmap(strip, 0f, y.toFloat(), null)
				y += strip.height
				strip.recycle()
			}
			// ...and the footer from the last frame.
			if (bottom > 0) {
				canvas.drawBitmap(last, Rect(0, height - bottom, width, height), Rect(0, y, width, y + bottom), null)
			}
			recycleFrames()
			return result
		}

		private fun recycleFrames() {
			if (last !== first) last.recycle()
			first.recycle()
		}
	}

	// ------------------------------------------------------------ floating bar

	private fun showBar() {
		val res = XPLauncher.moduleResources ?: return
		val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(displayId) ?: return
		val windowContext = context.createWindowContext(display, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
		val wm = windowContext.getSystemService(WindowManager::class.java) ?: return
		val density = windowContext.resources.displayMetrics.density
		fun dp(value: Int) = (value * density).toInt()

		val layout = LinearLayout(windowContext).apply {
			orientation = LinearLayout.HORIZONTAL
			gravity = Gravity.CENTER_VERTICAL
			background = GradientDrawable().apply {
				setColor(BAR_COLOR)
				cornerRadius = dp(28).toFloat()
			}
			elevation = dp(6).toFloat()
			setPadding(dp(6), dp(6), dp(6), dp(6))
		}

		fun button(text: Int, icon: Int, command: Command) = TextView(windowContext).apply {
			this.text = res.getString(text)
			setTextColor(Color.WHITE)
			textSize = 15f
			typeface = Typeface.DEFAULT_BOLD
			gravity = Gravity.CENTER_VERTICAL
			setPadding(dp(18), dp(12), dp(18), dp(12))
			if (icon != 0) {
				ResourcesCompat.getDrawable(res, icon, null)?.mutate()?.let {
					it.setTint(Color.WHITE)
					it.setBounds(0, 0, dp(20), dp(20))
					setCompoundDrawablesRelative(it, null, null, null)
					compoundDrawablePadding = dp(8)
				}
			}
			background = RippleDrawable(
				ColorStateList.valueOf(RIPPLE_COLOR),
				null,
				GradientDrawable().apply {
					setColor(Color.WHITE)
					cornerRadius = dp(22).toFloat()
				},
			)
			setOnClickListener { commands.offer(command) }
		}

		if (manual) {
			layout.addView(button(R.string.scroll_capture_scroll, R.drawable.ic_scroll_capture, Command.STEP))
			layout.addView(button(R.string.scroll_capture_done, 0, Command.FINISH))
		} else {
			layout.addView(button(R.string.scroll_capture_stop, 0, Command.FINISH))
		}

		val params = WindowManager.LayoutParams(
			WindowManager.LayoutParams.WRAP_CONTENT,
			WindowManager.LayoutParams.WRAP_CONTENT,
			WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
			WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
				WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
				WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
			PixelFormat.TRANSLUCENT,
		).apply {
			// Well below where the swipes start (72% of the height).
			gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
			y = dp(BAR_BOTTOM_MARGIN_DP)
			title = "PXN scroll capture"
		}
		wm.addView(layout, params)
		bar = layout
		windowManager = wm
	}

	/** Hiding waits a few frames, so the bar is off screen before the next capture. */
	private fun setBarVisible(visible: Boolean) {
		runOnMainSync { bar?.visibility = if (visible) View.VISIBLE else View.INVISIBLE }
		if (!visible) SystemClock.sleep(BAR_HIDE_DELAY)
	}

	private fun tearDownUi() {
		stopTapMonitor()
		val view = bar ?: return
		bar = null
		try {
			windowManager?.removeViewImmediate(view)
		} catch (t: Throwable) {
			Logger.log(t)
		}
	}

	// ------------------------------------------------------------ tap to stop

	/**
	 * SystemUI's InputMonitorCompat / InputChannelCompat, through reflection:
	 * a gesture monitor sees every touch on the display before the app does.
	 * Without it the bar buttons still work.
	 */
	private fun startTapMonitor() {
		try {
			val loader = imageCapture.javaClass.classLoader
			val monitorClass = Class.forName("com.android.systemui.shared.system.InputMonitorCompat", false, loader)
			val listenerClass = Class.forName("com.android.systemui.shared.system.InputChannelCompat\$InputEventListener", false, loader)
			val monitor = monitorClass.getConstructor(String::class.java, Int::class.javaPrimitiveType)
				.newInstance("PXN-ScrollCapture", displayId)
			val listener = Proxy.newProxyInstance(loader, arrayOf(listenerClass)) { proxy, method, args ->
				when (method.name) {
					"onInputEvent" -> {
						onUserInput(monitor, args?.getOrNull(0))
						null
					}
					"equals" -> proxy === args?.getOrNull(0)
					"hashCode" -> System.identityHashCode(proxy)
					else -> "PXN scroll capture tap monitor"
				}
			}
			inputReceiver = callMethod(monitor, "getInputReceiver", Looper.getMainLooper(), Choreographer.getInstance(), listener)
			inputMonitor = monitor
		} catch (t: Throwable) {
			Logger.log(t)
		}
	}

	/** A real finger going down outside the bar ends the capture. */
	private fun onUserInput(monitor: Any, event: Any?) {
		if (event !is MotionEvent || event.actionMasked != MotionEvent.ACTION_DOWN) return
		// Our own swipes come from no device (id 0).
		if (injecting || event.deviceId <= 0) return

		// Also while the bar is hidden for a step: a quick second tap on "Scroll"
		// must not end the capture. The hidden bar's window swallows that tap.
		val view = bar
		if (view != null && view.isAttachedToWindow) {
			val location = IntArray(2)
			view.getLocationOnScreen(location)
			val x = event.rawX.toInt()
			val y = event.rawY.toInt()
			if (x in location[0]..(location[0] + view.width) && y in location[1]..(location[1] + view.height)) return
		}

		try {
			callMethod(monitor, "pilferPointers")
		} catch (t: Throwable) {
			Logger.log(t)
		}
		commands.offer(Command.FINISH)
	}

	private fun stopTapMonitor() {
		inputReceiver?.let { runCatching { callMethod(it, "dispose") } }
		inputMonitor?.let { runCatching { callMethod(it, "dispose") } }
		inputReceiver = null
		inputMonitor = null
	}

	private fun runOnMainSync(block: () -> Unit) {
		if (Looper.myLooper() == Looper.getMainLooper()) {
			block()
			return
		}
		val latch = CountDownLatch(1)
		mainHandler.post {
			try {
				block()
			} catch (t: Throwable) {
				Logger.log(t)
			} finally {
				latch.countDown()
			}
		}
		latch.await(MAIN_SYNC_TIMEOUT, TimeUnit.MILLISECONDS)
	}

	// ------------------------------------------------------------ capture

	/** Software copy of the display; the capture itself is a hardware bitmap. */
	private fun capture(): Bitmap? {
		val bitmap = captureDisplay.invoke(imageCapture, *captureArgs) as? Bitmap ?: return null
		val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
		bitmap.recycle()
		return copy
	}

	/** Captures until two frames in a row match, so smooth-scroll and overscroll have ended. */
	private fun captureSettled(): Pair<Bitmap, LongArray>? {
		SystemClock.sleep(SETTLE_DELAY)
		var prev = capture() ?: return null
		var prevHashes = rowHashes(prev)
		repeat(SETTLE_TRIES) {
			SystemClock.sleep(SETTLE_INTERVAL)
			val next = capture() ?: return prev to prevHashes
			val nextHashes = rowHashes(next)
			if (nextHashes.contentEquals(prevHashes)) {
				next.recycle()
				return prev to prevHashes
			}
			prev.recycle()
			prev = next
			prevHashes = nextHashes
		}
		return prev to prevHashes
	}

	// ------------------------------------------------------------ input

	/**
	 * A slow vertical drag that holds still before lifting, so the view scrolls
	 * by about the drag length and does not fling.
	 */
	private fun swipe(x: Float, fromY: Float, toY: Float) {
		injecting = true
		try {
			val downTime = SystemClock.uptimeMillis()
			inject(MotionEvent.ACTION_DOWN, x, fromY, downTime)
			for (i in 1..SWIPE_MOVES) {
				SystemClock.sleep(SWIPE_MOVE_INTERVAL)
				inject(MotionEvent.ACTION_MOVE, x, fromY + (toY - fromY) * i / SWIPE_MOVES, downTime)
			}
			repeat(SWIPE_HOLD_MOVES) {
				SystemClock.sleep(SWIPE_MOVE_INTERVAL)
				inject(MotionEvent.ACTION_MOVE, x, toY, downTime)
			}
			inject(MotionEvent.ACTION_UP, x, toY, downTime)
			// The monitor gets the injected events asynchronously.
			SystemClock.sleep(SWIPE_MOVE_INTERVAL * 3)
		} finally {
			injecting = false
		}
	}

	/** InputManager.injectInputEvent is hidden; SystemUI holds INJECT_EVENTS. */
	@SuppressLint("PrivateApi")
	private fun inject(action: Int, x: Float, y: Float, downTime: Long) {
		val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
		event.source = InputDevice.SOURCE_TOUCHSCREEN
		try {
			callMethod(event, "setDisplayId", displayId)
		} catch (ignored: Throwable) {
		}
		try {
			val inputManager = context.getSystemService(InputManager::class.java)
			InputManager::class.java
				.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
				.invoke(inputManager, event, INJECT_MODE_ASYNC)
		} finally {
			event.recycle()
		}
	}

	// ------------------------------------------------------------ stitching

	private fun statusBarHeight(): Int {
		@SuppressLint("DiscouragedApi", "InternalInsetResource")
		val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
		return if (id != 0) context.resources.getDimensionPixelSize(id) else 0
	}

	/** Rows below the status bar that did not move between the two frames. */
	private fun fixedTop(a: LongArray, b: LongArray, statusBar: Int): Int {
		var row = statusBar.coerceIn(0, a.size)
		while (row < a.size && a[row] == b[row]) row++
		return row.coerceAtMost(a.size / 2)
	}

	private fun fixedBottom(a: LongArray, b: LongArray, top: Int): Int {
		var count = 0
		while (count < a.size - top && a[a.size - 1 - count] == b[b.size - 1 - count]) count++
		return count.coerceAtMost(a.size / 3)
	}

	/**
	 * How far the content of [cur] moved up compared to [prev] inside the
	 * scrolling area, 0 if it did not move and -1 if no shift fits.
	 */
	private fun findShift(prev: LongArray, cur: LongArray, top: Int, regionHeight: Int, expected: Int): Int {
		if (mismatchRatio(prev, cur, top, regionHeight, 0) < END_RATIO) return 0

		val minOverlap = maxOf(regionHeight / 8, MIN_OVERLAP)
		var bestShift = -1
		var bestRatio = Float.MAX_VALUE
		for (shift in 1..(regionHeight - minOverlap)) {
			val ratio = mismatchRatio(prev, cur, top, regionHeight, shift)
			if (ratio < 0f) continue
			val better = when {
				ratio <= MATCH_RATIO && bestRatio <= MATCH_RATIO ->
					abs(shift - expected) < abs(bestShift - expected)
				else -> ratio < bestRatio
			}
			if (better) {
				bestRatio = ratio
				bestShift = shift
			}
		}
		return if (bestRatio <= ACCEPT_RATIO) bestShift else -1
	}

	/**
	 * Share of informative rows of the overlap that differ when [cur] is
	 * shifted up by [shift]; -1 when the overlap has too few informative
	 * (non-blank) rows to tell.
	 */
	private fun mismatchRatio(prev: LongArray, cur: LongArray, top: Int, regionHeight: Int, shift: Int): Float {
		val overlap = regionHeight - shift
		var checked = 0
		var mismatches = 0
		var i = 0
		while (i < overlap) {
			val c = cur[top + i]
			if (c != BLANK_ROW) {
				checked++
				if (c != prev[top + shift + i]) mismatches++
			}
			i += ROW_STRIDE
		}
		if (checked < MIN_INFORMATIVE_ROWS) return -1f
		return mismatches.toFloat() / checked
	}

	/**
	 * One hash per row, skipping the right edge where scrollbars fade in and
	 * move. Rows of a single color hash to [BLANK_ROW] so they never decide a
	 * match on their own.
	 */
	private fun rowHashes(bitmap: Bitmap): LongArray {
		val width = bitmap.width
		val usable = (width * 0.94f).toInt()
		val row = IntArray(width)
		return LongArray(bitmap.height) { y ->
			bitmap.getPixels(row, 0, width, 0, y, width, 1)
			val firstPixel = row[0]
			var uniform = true
			var hash = 1125899906842597L
			var x = 0
			while (x < usable) {
				val pixel = row[x]
				if (pixel != firstPixel) uniform = false
				hash = 31 * hash + pixel
				x += 2
			}
			if (uniform) BLANK_ROW else hash
		}
	}

	// ------------------------------------------------------------ output

	private fun save(bitmap: Bitmap): Uri? {
		val name = "Screenshot_" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + "_scroll.png"
		val resolver = context.contentResolver
		val values = ContentValues().apply {
			put(MediaStore.MediaColumns.DISPLAY_NAME, name)
			put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
			put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Screenshots")
			put(MediaStore.MediaColumns.IS_PENDING, 1)
		}
		val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
		return try {
			resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
				?: throw IllegalStateException("No output stream for $uri")
			resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
			uri
		} catch (t: Throwable) {
			Logger.log(t)
			resolver.delete(uri, null, null)
			null
		}
	}

	/** Opens the result in the screenshot editor (crop / markup), like the platform long screenshot. */
	@SuppressLint("DiscouragedApi")
	private fun openResult(uri: Uri) {
		val intent = Intent(Intent.ACTION_EDIT)
			.setDataAndType(uri, "image/png")
			.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
			.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
		val editorId = context.resources.getIdentifier("config_screenshotEditor", "string", context.packageName)
		val editor = if (editorId != 0) context.getString(editorId) else ""
		if (editor.isNotEmpty()) intent.component = ComponentName.unflattenFromString(editor)
		try {
			context.startActivity(intent)
		} catch (t: Throwable) {
			try {
				context.startActivity(intent.setComponent(null).setAction(Intent.ACTION_VIEW))
			} catch (t2: Throwable) {
				Logger.log(t2)
			}
		}
	}

	private fun toast(text: Int) {
		val message = XPLauncher.moduleResources?.getString(text) ?: return
		mainHandler.post { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
	}

	companion object {
		/** One capture at a time; set by the caller before [start]. */
		@Volatile
		@JvmStatic
		var running = false

		private const val INJECT_MODE_ASYNC = 0
		private const val SWIPE_MOVES = 20
		private const val SWIPE_HOLD_MOVES = 8
		private const val SWIPE_MOVE_INTERVAL = 16L

		private const val SETTLE_DELAY = 250L
		private const val SETTLE_INTERVAL = 120L
		private const val SETTLE_TRIES = 6

		private const val ROW_STRIDE = 3
		private const val MIN_INFORMATIVE_ROWS = 12
		private const val MIN_OVERLAP = 120
		private const val MIN_REGION = 300
		private const val END_RATIO = 0.01f
		private const val MATCH_RATIO = 0.02f
		private const val ACCEPT_RATIO = 0.10f
		private const val BLANK_ROW = 0L

		/** Keeps the stitched bitmap within a sane size for SystemUI. */
		private const val MAX_HEIGHT = 20000

		/** Manual mode gives up (and saves) when the bar is left alone this long, in seconds. */
		private const val MANUAL_TIMEOUT = 60L
		/** Automatic mode: pause between scrolls with the bar shown, in ms. */
		private const val AUTO_PAUSE = 350L
		private const val BAR_HIDE_DELAY = 80L
		private const val BAR_BOTTOM_MARGIN_DP = 96
		private const val MAIN_SYNC_TIMEOUT = 2000L
		private const val BAR_COLOR = 0xF0202124.toInt()
		private const val RIPPLE_COLOR = 0x33FFFFFF
	}
}
