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
import kotlin.math.max

/**
 * The touch strip of one screen edge, living in its own overlay window.
 *
 * It classifies what the finger does on the strip into one of the [GESTURES]
 * and hands the result to [Listener]; what each gesture does is the owner's
 * business. Coordinates are read raw, because a swipe leaves the narrow
 * window right away and the window keeps receiving the stream it started.
 *
 * Taps are counted rather than detected one by one: a single tap waits for
 * the double-tap timeout only when the owner says a double tap means
 * something on this edge ([waitForDoubleTap]), so an edge without a double
 * tap action answers a tap at once. A long press fires while the finger is
 * still down and swallows the rest of that touch.
 */
@SuppressLint("ViewConstructor")
class EdgeGestureView(
	context: Context,
	val side: Int,
	private val listener: Listener,
) : View(context) {

	interface Listener {
		fun onEdgeGesture(view: EdgeGestureView, gesture: Int)

		fun onEdgeConfigurationChanged(view: EdgeGestureView)
	}

	companion object {
		const val SIDE_LEFT = 0
		const val SIDE_RIGHT = 1

		const val GESTURE_TAP = 0
		const val GESTURE_DOUBLE_TAP = 1
		const val GESTURE_LONG_PRESS = 2
		const val GESTURE_SWIPE_IN = 3
		const val GESTURE_SWIPE_UP = 4
		const val GESTURE_SWIPE_DOWN = 5

		val GESTURES = intArrayOf(
			GESTURE_TAP,
			GESTURE_DOUBLE_TAP,
			GESTURE_LONG_PRESS,
			GESTURE_SWIPE_IN,
			GESTURE_SWIPE_UP,
			GESTURE_SWIPE_DOWN,
		)
	}

	/** Travel, in pixels, a touch needs before it counts as a swipe. */
	var swipeThreshold = 0f

	/** Whether a tap has to wait to see if a second one follows. */
	var waitForDoubleTap = false

	/** Whether a press held in place counts as a long press at all. */
	var longPressEnabled = false

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
	private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
	private val doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout().toLong()
	private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()

	private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = handleColor }
	private val handleRect = RectF()

	private var downX = 0f
	private var downY = 0f
	private var moved = false
	private var longPressFired = false
	private var tapCount = 0
	private var pressed = false

	private val longPressRunnable = Runnable {
		longPressFired = true
		tapCount = 0
		fire(GESTURE_LONG_PRESS)
	}

	private val tapRunnable = Runnable {
		tapCount = 0
		fire(GESTURE_TAP)
	}

	@SuppressLint("ClickableViewAccessibility")
	override fun onTouchEvent(event: MotionEvent): Boolean {
		when (event.actionMasked) {
			MotionEvent.ACTION_DOWN -> {
				downX = event.rawX
				downY = event.rawY
				moved = false
				longPressFired = false
				setPressedHandle(true)
				if (longPressEnabled) {
					mainHandler.postDelayed(longPressRunnable, longPressTimeout)
				}
			}

			MotionEvent.ACTION_MOVE -> {
				if (!moved &&
					max(abs(event.rawX - downX), abs(event.rawY - downY)) > touchSlop
				) {
					moved = true
					mainHandler.removeCallbacks(longPressRunnable)
				}
			}

			MotionEvent.ACTION_UP -> {
				mainHandler.removeCallbacks(longPressRunnable)
				setPressedHandle(false)

				if (longPressFired) return true

				if (moved) {
					tapCount = 0
					mainHandler.removeCallbacks(tapRunnable)
					classifySwipe(event.rawX - downX, event.rawY - downY)?.let { fire(it) }
				} else {
					onTap()
				}
			}

			// The system took the stream - a back gesture this strip does not
			// claim, most likely. Nothing the finger did here counts.
			MotionEvent.ACTION_CANCEL -> {
				mainHandler.removeCallbacks(longPressRunnable)
				setPressedHandle(false)
			}
		}
		return true
	}

	private fun onTap() {
		mainHandler.removeCallbacks(tapRunnable)
		tapCount++

		if (tapCount >= 2) {
			tapCount = 0
			fire(GESTURE_DOUBLE_TAP)
		} else if (waitForDoubleTap) {
			mainHandler.postDelayed(tapRunnable, doubleTapTimeout)
		} else {
			tapCount = 0
			fire(GESTURE_TAP)
		}
	}

	/**
	 * Inward travel is positive whichever edge the strip hangs on. A swipe
	 * outwards cannot leave the screen, so it means nothing.
	 */
	private fun classifySwipe(dx: Float, dy: Float): Int? {
		val inward = if (side == SIDE_LEFT) dx else -dx

		if (max(abs(dx), abs(dy)) < swipeThreshold) return null

		return if (abs(dx) > abs(dy)) {
			if (inward > 0) GESTURE_SWIPE_IN else null
		} else {
			if (dy < 0) GESTURE_SWIPE_UP else GESTURE_SWIPE_DOWN
		}
	}

	private fun fire(gesture: Int) {
		listener.onEdgeGesture(this, gesture)
	}

	private fun setPressedHandle(isPressed: Boolean) {
		if (pressed == isPressed) return
		pressed = isPressed
		invalidate()
	}

	override fun onDraw(canvas: Canvas) {
		if (!showHandle) return

		// A thin bar against the screen edge, half as wide as the strip, which
		// thickens while it is touched.
		val barWidth = if (pressed) width.toFloat() else width / 2f
		if (side == SIDE_LEFT) {
			handleRect.set(0f, 0f, barWidth, height.toFloat())
		} else {
			handleRect.set(width - barWidth, 0f, width.toFloat(), height.toFloat())
		}
		val radius = barWidth / 2f
		canvas.drawRoundRect(handleRect, radius, radius, handlePaint)
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
		mainHandler.removeCallbacks(longPressRunnable)
		mainHandler.removeCallbacks(tapRunnable)
		tapCount = 0
		pressed = false
	}
}
