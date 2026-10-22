package sh.anirudhgupta109.pxn.xposed.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * The touch strip of one screen edge, living in its own overlay window.
 *
 * It classifies what the finger does on the strip into one of the gestures
 * below and hands the result to [Listener]; what each gesture does is the
 * owner's business. Everything is measured on two axes relative to the edge,
 * so one classifier serves all three edges: *inward* is towards the middle of
 * the screen, *along* runs parallel to the edge - downwards on the side
 * edges, rightwards on the bottom one. "Up" in a gesture name is therefore
 * "left" on the bottom edge, and "down" is "right".
 *
 * A stroke is decided by the direction it takes once it has travelled
 * [swipeThreshold]: straight inwards, diagonally (25-65 degrees), or along the
 * edge. From there it can still turn into
 *  - a hold, when the finger then rests for [holdTimeout] (fires at once),
 *  - a back-and-forth, when it returns by [swipeThreshold] again,
 *  - a long swipe, when an inward stroke reaches [longSwipeThreshold],
 *  - pull and slide, when an inward stroke goes on to move along the edge
 *    and [adjustEnabled] is set: the listener then receives the along travel
 *    until the finger lifts, and no gesture fires.
 * A gesture without an action falls back to the plainer one it extends
 * (a long or diagonal swipe to the straight swipe, a back-and-forth to its
 * first stroke), so binding only the basics keeps working for every stroke.
 *
 * Taps are counted: a tap only waits for a second (or third) one when a
 * double (or triple) tap means something on this edge.
 */
@SuppressLint("ViewConstructor")
class EdgeGestureView(
	context: Context,
	val side: Int,
	private val listener: Listener,
) : View(context) {

	interface Listener {
		fun onEdgeGesture(view: EdgeGestureView, gesture: Int, rawX: Float, rawY: Float)

		/**
		 * The finger moved. [along] is its raw coordinate on the along axis,
		 * [pull] how far inwards it is from where it started (never negative),
		 * [candidate] the gesture a release now would fire, or -1.
		 * [active] is false once the touch is over.
		 */
		fun onEdgeTrack(view: EdgeGestureView, along: Float, pull: Float, candidate: Int, active: Boolean)

		fun onEdgeAdjustStart(view: EdgeGestureView)

		/** [delta] is the along travel since the last call, in pixels. */
		fun onEdgeAdjust(view: EdgeGestureView, delta: Float)

		fun onEdgeAdjustEnd(view: EdgeGestureView)

		fun onEdgeConfigurationChanged(view: EdgeGestureView)
	}

	companion object {
		const val SIDE_LEFT = 0
		const val SIDE_RIGHT = 1
		const val SIDE_BOTTOM = 2

		const val NO_GESTURE = -1
		const val GESTURE_TAP = 0
		const val GESTURE_DOUBLE_TAP = 1
		const val GESTURE_TRIPLE_TAP = 2
		const val GESTURE_LONG_PRESS = 3
		const val GESTURE_SWIPE_IN = 4
		const val GESTURE_SWIPE_IN_HOLD = 5
		const val GESTURE_SWIPE_IN_OUT = 6
		const val GESTURE_LONG_SWIPE_IN = 7
		const val GESTURE_SWIPE_UP = 8
		const val GESTURE_SWIPE_UP_HOLD = 9
		const val GESTURE_SWIPE_UP_DOWN = 10
		const val GESTURE_SWIPE_DOWN = 11
		const val GESTURE_SWIPE_DOWN_HOLD = 12
		const val GESTURE_SWIPE_DOWN_UP = 13
		const val GESTURE_SWIPE_DIAG_UP = 14
		const val GESTURE_SWIPE_DIAG_DOWN = 15
		const val GESTURE_LONG_SWIPE_DIAG_UP = 16
		const val GESTURE_LONG_SWIPE_DIAG_DOWN = 17

		const val GESTURE_COUNT = 18

		/** Preference key suffix of each gesture, by gesture id. */
		val GESTURE_KEYS = arrayOf(
			"Tap", "DoubleTap", "TripleTap", "LongPress",
			"SwipeIn", "SwipeInHold", "SwipeInOut", "LongSwipeIn",
			"SwipeUp", "SwipeUpHold", "SwipeUpDown",
			"SwipeDown", "SwipeDownHold", "SwipeDownUp",
			"SwipeDiagUp", "SwipeDiagDown", "LongSwipeDiagUp", "LongSwipeDiagDown",
		)

		private const val DIR_NONE = 0
		private const val DIR_IN = 1
		private const val DIR_OUT = 2
		private const val DIR_ALONG_NEG = 3
		private const val DIR_ALONG_POS = 4
		private const val DIR_DIAG_NEG = 5
		private const val DIR_DIAG_POS = 6

		private const val DIAG_MIN_DEGREES = 25.0
		private const val DIAG_MAX_DEGREES = 65.0
	}

	/** Travel, in pixels, a touch needs before it counts as a swipe. */
	var swipeThreshold = 0f

	/** Inward travel, in pixels, that turns a swipe into a long swipe. */
	var longSwipeThreshold = 0f

	/** How long a finger has to rest before a swipe becomes a hold. */
	var holdTimeout = 500L

	/** Which gestures have an action on this edge, by gesture id. */
	val bound = BooleanArray(GESTURE_COUNT)

	/** Whether an inward stroke that turns along the edge adjusts a value. */
	var adjustEnabled = false

	/** Whether the strip is painted; an unpainted strip still takes touches. */
	var showHandle = false
		set(value) {
			field = value
			invalidate()
		}

	var handleColor = Color.WHITE
		set(value) {
			field = value
			handlePaint.color = value
			invalidate()
		}

	/** Whether the strip claims its area from the system back gesture. */
	var excludeSystemGesture = true
		set(value) {
			field = value
			updateExclusion()
		}

	private val mainHandler = Handler(Looper.getMainLooper())
	private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
	private val doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout().toLong()
	private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()

	private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = handleColor }
	private val handleRect = RectF()

	private var downX = 0f
	private var downY = 0f
	private var lastX = 0f
	private var lastY = 0f
	private var moved = false
	private var done = false
	private var direction = DIR_NONE
	private var reversed = false
	private var maxInward = 0f
	private var extremeAlong = 0f
	private var alongAtTurn = 0f
	private var holdAnchorX = 0f
	private var holdAnchorY = 0f
	private var adjusting = false
	private var lastAdjustAlong = 0f
	private var tapCount = 0
	private var tapX = 0f
	private var tapY = 0f
	private var pressed = false

	private val longPressRunnable = Runnable {
		done = true
		tapCount = 0
		fire(GESTURE_LONG_PRESS, downX, downY)
	}

	private val tapRunnable = Runnable {
		val count = tapCount
		tapCount = 0
		fire(if (count >= 2) GESTURE_DOUBLE_TAP else GESTURE_TAP, tapX, tapY)
	}

	private val holdRunnable = Runnable {
		if (done || reversed || adjusting) return@Runnable
		val gesture = when (direction) {
			DIR_IN, DIR_DIAG_NEG, DIR_DIAG_POS -> GESTURE_SWIPE_IN_HOLD
			DIR_ALONG_NEG -> GESTURE_SWIPE_UP_HOLD
			DIR_ALONG_POS -> GESTURE_SWIPE_DOWN_HOLD
			else -> return@Runnable
		}
		if (!bound[gesture]) return@Runnable
		done = true
		fire(gesture, lastX, lastY)
		track(false)
	}

	private fun inward(dx: Float, dy: Float): Float = when (side) {
		SIDE_LEFT -> dx
		SIDE_RIGHT -> -dx
		else -> -dy
	}

	private fun along(dx: Float, dy: Float): Float = if (side == SIDE_BOTTOM) dx else dy

	private fun rawAlong(x: Float, y: Float): Float = if (side == SIDE_BOTTOM) x else y

	@SuppressLint("ClickableViewAccessibility")
	override fun onTouchEvent(event: MotionEvent): Boolean {
		val x = event.rawX
		val y = event.rawY

		when (event.actionMasked) {
			MotionEvent.ACTION_DOWN -> {
				downX = x
				downY = y
				lastX = x
				lastY = y
				moved = false
				done = false
				direction = DIR_NONE
				reversed = false
				adjusting = false
				maxInward = 0f
				setPressedHandle(true)
				if (bound[GESTURE_LONG_PRESS]) {
					mainHandler.postDelayed(longPressRunnable, longPressTimeout)
				}
			}

			MotionEvent.ACTION_MOVE -> {
				lastX = x
				lastY = y
				if (done) return true
				onMove(x - downX, y - downY, x, y)
			}

			MotionEvent.ACTION_UP -> {
				cancelPending()
				setPressedHandle(false)

				if (adjusting) {
					adjusting = false
					listener.onEdgeAdjustEnd(this)
				} else if (!done) {
					if (!moved) {
						onTap(x, y)
					} else if (direction != DIR_NONE) {
						val gesture = resolve()
						if (gesture != NO_GESTURE) fire(gesture, x, y)
					}
				}
				done = true
				track(false)
			}

			// The system took the stream - a back gesture this strip does not
			// claim, most likely. Nothing the finger did here counts.
			MotionEvent.ACTION_CANCEL -> {
				cancelPending()
				setPressedHandle(false)
				if (adjusting) {
					adjusting = false
					listener.onEdgeAdjustEnd(this)
				}
				done = true
				track(false)
			}
		}
		return true
	}

	private fun onMove(dx: Float, dy: Float, x: Float, y: Float) {
		val inward = inward(dx, dy)
		val along = along(dx, dy)

		if (!moved && hypot(dx, dy) > touchSlop) {
			moved = true
			mainHandler.removeCallbacks(longPressRunnable)
		}

		if (direction == DIR_NONE) {
			if (hypot(dx, dy) < swipeThreshold) return
			direction = classify(inward, along)
			maxInward = inward
			extremeAlong = along
			alongAtTurn = along
			anchorHold(x, y)
		} else if (adjusting) {
			val rawAlong = rawAlong(x, y)
			listener.onEdgeAdjust(this, rawAlong - lastAdjustAlong)
			lastAdjustAlong = rawAlong
			track(true)
			return
		} else {
			when (direction) {
				DIR_IN, DIR_DIAG_NEG, DIR_DIAG_POS -> {
					if (inward > maxInward) {
						maxInward = inward
						alongAtTurn = along
					}
					if (maxInward - inward >= swipeThreshold) reversed = true

					if (adjustEnabled && direction == DIR_IN && !reversed &&
						abs(along - alongAtTurn) > touchSlop * 2
					) {
						adjusting = true
						mainHandler.removeCallbacks(holdRunnable)
						lastAdjustAlong = rawAlong(x, y)
						listener.onEdgeAdjustStart(this)
					}
				}

				DIR_ALONG_NEG -> {
					if (along < extremeAlong) extremeAlong = along
					if (along - extremeAlong >= swipeThreshold) reversed = true
				}

				DIR_ALONG_POS -> {
					if (along > extremeAlong) extremeAlong = along
					if (extremeAlong - along >= swipeThreshold) reversed = true
				}
			}

			if (hypot(x - holdAnchorX, y - holdAnchorY) > touchSlop) anchorHold(x, y)
		}
		maxInward = maxOf(maxInward, inward)
		track(true)
	}

	/** Restarts the hold countdown from where the finger is now. */
	private fun anchorHold(x: Float, y: Float) {
		holdAnchorX = x
		holdAnchorY = y
		mainHandler.removeCallbacks(holdRunnable)
		if (bound[GESTURE_SWIPE_IN_HOLD] || bound[GESTURE_SWIPE_UP_HOLD] || bound[GESTURE_SWIPE_DOWN_HOLD]) {
			mainHandler.postDelayed(holdRunnable, holdTimeout)
		}
	}

	private fun classify(inward: Float, along: Float): Int {
		if (inward <= 0f) {
			return if (abs(along) > abs(inward)) {
				if (along < 0) DIR_ALONG_NEG else DIR_ALONG_POS
			} else DIR_OUT
		}

		val degrees = Math.toDegrees(atan2(abs(along), inward).toDouble())
		return when {
			degrees < DIAG_MIN_DEGREES -> DIR_IN
			degrees <= DIAG_MAX_DEGREES -> if (along < 0) DIR_DIAG_NEG else DIR_DIAG_POS
			along < 0 -> DIR_ALONG_NEG
			else -> DIR_ALONG_POS
		}
	}

	/** The gesture a release would fire now, after the fallbacks. */
	private fun resolve(): Int {
		val long = longSwipeThreshold > 0f && maxInward >= longSwipeThreshold

		val wanted: IntArray = when (direction) {
			DIR_IN ->
				if (reversed) intArrayOf(GESTURE_SWIPE_IN_OUT, GESTURE_SWIPE_IN)
				else if (long) intArrayOf(GESTURE_LONG_SWIPE_IN, GESTURE_SWIPE_IN)
				else intArrayOf(GESTURE_SWIPE_IN)

			DIR_DIAG_NEG ->
				if (long) intArrayOf(GESTURE_LONG_SWIPE_DIAG_UP, GESTURE_SWIPE_DIAG_UP, GESTURE_LONG_SWIPE_IN, GESTURE_SWIPE_IN)
				else intArrayOf(GESTURE_SWIPE_DIAG_UP, GESTURE_SWIPE_IN)

			DIR_DIAG_POS ->
				if (long) intArrayOf(GESTURE_LONG_SWIPE_DIAG_DOWN, GESTURE_SWIPE_DIAG_DOWN, GESTURE_LONG_SWIPE_IN, GESTURE_SWIPE_IN)
				else intArrayOf(GESTURE_SWIPE_DIAG_DOWN, GESTURE_SWIPE_IN)

			DIR_ALONG_NEG ->
				if (reversed) intArrayOf(GESTURE_SWIPE_UP_DOWN, GESTURE_SWIPE_UP)
				else intArrayOf(GESTURE_SWIPE_UP)

			DIR_ALONG_POS ->
				if (reversed) intArrayOf(GESTURE_SWIPE_DOWN_UP, GESTURE_SWIPE_DOWN)
				else intArrayOf(GESTURE_SWIPE_DOWN)

			else -> intArrayOf()
		}
		return wanted.firstOrNull { bound[it] } ?: NO_GESTURE
	}

	private fun onTap(x: Float, y: Float) {
		mainHandler.removeCallbacks(tapRunnable)
		tapCount++
		tapX = x
		tapY = y

		val waitForMore = when (tapCount) {
			1 -> bound[GESTURE_DOUBLE_TAP] || bound[GESTURE_TRIPLE_TAP]
			2 -> bound[GESTURE_TRIPLE_TAP]
			else -> false
		}

		when {
			waitForMore -> mainHandler.postDelayed(tapRunnable, doubleTapTimeout)
			tapCount >= 3 -> {
				tapCount = 0
				fire(GESTURE_TRIPLE_TAP, x, y)
			}
			else -> tapRunnable.run()
		}
	}

	private fun track(active: Boolean) {
		val candidate = if (active && !adjusting && direction != DIR_NONE) resolve() else NO_GESTURE
		listener.onEdgeTrack(
			this,
			rawAlong(lastX, lastY),
			maxOf(0f, inward(lastX - downX, lastY - downY)),
			candidate,
			active && !done,
		)
	}

	private fun cancelPending() {
		mainHandler.removeCallbacks(longPressRunnable)
		mainHandler.removeCallbacks(holdRunnable)
	}

	private fun fire(gesture: Int, x: Float, y: Float) {
		listener.onEdgeGesture(this, gesture, x, y)
	}

	private fun setPressedHandle(isPressed: Boolean) {
		if (pressed == isPressed) return
		pressed = isPressed
		invalidate()
	}

	override fun onDraw(canvas: Canvas) {
		if (!showHandle) return

		// A thin bar against the screen edge, half as thick as the strip,
		// which thickens while it is touched.
		val w = width.toFloat()
		val h = height.toFloat()
		if (side == SIDE_BOTTOM) {
			val bar = if (pressed) h else h / 2f
			handleRect.set(0f, h - bar, w, h)
			canvas.drawRoundRect(handleRect, bar / 2f, bar / 2f, handlePaint)
		} else {
			val bar = if (pressed) w else w / 2f
			if (side == SIDE_LEFT) handleRect.set(0f, 0f, bar, h)
			else handleRect.set(w - bar, 0f, w, h)
			canvas.drawRoundRect(handleRect, bar / 2f, bar / 2f, handlePaint)
		}
	}

	override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
		super.onLayout(changed, left, top, right, bottom)
		updateExclusion()
	}

	private fun updateExclusion() {
		systemGestureExclusionRects =
			if (excludeSystemGesture) listOf(Rect(0, 0, width, height))
			else emptyList()
	}

	override fun onConfigurationChanged(newConfig: Configuration) {
		super.onConfigurationChanged(newConfig)
		listener.onEdgeConfigurationChanged(this)
	}

	override fun onDetachedFromWindow() {
		super.onDetachedFromWindow()
		cancelPending()
		mainHandler.removeCallbacks(tapRunnable)
		tapCount = 0
		pressed = false
	}
}
