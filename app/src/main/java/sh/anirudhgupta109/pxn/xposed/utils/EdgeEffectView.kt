package sh.anirudhgupta109.pxn.xposed.utils

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import kotlin.math.atan2
import sh.anirudhgupta109.pxn.xposed.utils.EdgeGestureView.Companion.SIDE_BOTTOM
import sh.anirudhgupta109.pxn.xposed.utils.EdgeGestureView.Companion.SIDE_LEFT
import sh.anirudhgupta109.pxn.xposed.utils.EdgeGestureView.Companion.SIDE_RIGHT

/**
 * What a touch on an edge strip looks like - drawn the way the Edge Gestures
 * app draws it.
 *
 * The wave is anchored where the finger came down: its crest is a third of
 * the pull away from the edge (capped at [waveHeight]), the crest drifts a
 * quarter of the finger's travel along the edge (capped at a quarter of
 * [waveLength]), and the outline is the app's two cubic curves - control
 * points at 0.3 and 0.13 of the length, 0.2 of the height. On release the
 * crest falls back to the edge in 200ms and the action badge slides off the
 * screen after 250ms.
 *
 * The badge is the app's 53dp action icon at the edge, centred on the touch
 * point, on [iconBackground]; next to it, optionally, the action's name. With
 * [diagonalRange] on, the sector a diagonal swipe has to stay in is shaded
 * while the finger is in it (the app's "show diagonal range"). During pull
 * and slide the name is replaced by the level being adjusted.
 *
 * It lives in a window of its own that takes no touches, laid over the whole
 * length of the edge. Coordinates come in screen space and are converted with
 * the view's own position.
 */
@SuppressLint("ViewConstructor")
class EdgeEffectView(context: Context, val side: Int) : View(context) {

	companion object {
		private const val CONTROL_LENGTH = 0.3f
		private const val CONTROL_CREST = 0.13f
		private const val CONTROL_HEIGHT = 0.2f
		private const val PULL_FACTOR = 3f
		private const val DRIFT_FACTOR = 4f
		private const val RANGE_RADIUS_DP = 250f
		private const val BADGE_DP = 53f
		private const val BADGE_PADDING_DP = 5f
	}

	var waveColor = Color.WHITE
		set(value) {
			field = value
			wavePaint.color = value
		}

	/** Largest crest, in pixels. */
	var waveHeight = 0f

	/** Length of the wave along the edge, in pixels. */
	var waveLength = 0f

	var showWave = true

	var showBadge = true

	var showLabel = true

	var diagonalRange = false

	var iconBackground = 0xFF3F51B5.toInt()
		set(value) {
			field = value
			badgePaint.color = value
		}

	/** The tint system glyphs get; set on the drawables by the owner. */
	var iconForeground = Color.WHITE

	private val density = resources.displayMetrics.density
	private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
	private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = iconBackground }
	private val rangePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
	private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		color = Color.WHITE
		textSize = 14 * density
	}
	private val labelBackground = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC202124.toInt() }
	private val levelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8AB4F8.toInt() }
	private val path = Path()
	private val rect = RectF()
	private val location = IntArray(2)

	private var active = false
	private var downX = 0f
	private var downY = 0f
	private var x = 0f
	private var y = 0f
	private var crest = 0f
	private var label: String? = null
	private var icon: Drawable? = null
	private var level = -1f
	private var badgeOffset = 0f
	private var badgeVisible = false

	private var crestAnimator: ValueAnimator? = null
	private var badgeAnimator: ValueAnimator? = null

	/**
	 * The finger is at ([x], [y]) after coming down at ([downX], [downY]).
	 * [label] and [icon] describe the action a release would run.
	 */
	fun track(downX: Float, downY: Float, x: Float, y: Float, label: String?, icon: Drawable?) {
		crestAnimator?.cancel()
		badgeAnimator?.cancel()
		badgeOffset = 0f
		badgeVisible = true
		active = true
		this.downX = downX
		this.downY = downY
		this.x = x
		this.y = y
		// While a value is being adjusted the level label stays: the finger
		// keeps reporting no gesture, which would otherwise clear it.
		if (level < 0f) {
			this.label = label
			this.icon = icon
		}
		crest = (pull() / PULL_FACTOR).coerceIn(0f, waveHeight)
		invalidate()
	}

	/** The finger lifted: the crest recedes and the badge leaves, as in Edge Gestures. */
	fun release() {
		if (!active) return
		level = -1f
		active = false

		crestAnimator?.cancel()
		crestAnimator = ValueAnimator.ofFloat(crest, 0f).apply {
			duration = 200
			interpolator = DecelerateInterpolator()
			addUpdateListener {
				crest = it.animatedValue as Float
				invalidate()
			}
			start()
		}

		badgeAnimator?.cancel()
		badgeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
			startDelay = 250
			duration = 150
			interpolator = AccelerateInterpolator()
			addUpdateListener {
				badgeOffset = it.animatedValue as Float
				if (badgeOffset >= 1f) {
					badgeVisible = false
					label = null
					icon = null
				}
				invalidate()
			}
			start()
		}
	}

	/** Shows [label] and a [level] bar (0..1) while a value is being adjusted. */
	fun showLevel(label: String, level: Float) {
		active = true
		this.label = label
		this.icon = null
		this.level = level.coerceIn(0f, 1f)
		crest = waveHeight
		invalidate()
	}

	private fun pull(): Float = when (side) {
		SIDE_LEFT -> x - downX
		SIDE_RIGHT -> downX - x
		else -> downY - y
	}

	override fun onDraw(canvas: Canvas) {
		getLocationOnScreen(location)
		val localDownX = downX - location[0]
		val localDownY = downY - location[1]

		if (showWave && crest > 0f) drawWave(canvas, localDownX, localDownY)
		if (!active && !badgeVisible) return

		if (diagonalRange && active && level < 0f) drawRange(canvas, localDownX, localDownY)

		val badgeSize = BADGE_DP * density
		val slide = badgeOffset * badgeSize
		val badgeLeft: Float
		val badgeTop: Float
		when (side) {
			SIDE_LEFT -> {
				badgeLeft = -slide
				badgeTop = localDownY - badgeSize / 2
			}
			SIDE_RIGHT -> {
				badgeLeft = width - badgeSize + slide
				badgeTop = localDownY - badgeSize / 2
			}
			else -> {
				badgeLeft = localDownX - badgeSize / 2
				badgeTop = height - badgeSize + slide
			}
		}

		val drawable = icon
		if (showBadge && drawable != null && level < 0f) {
			canvas.drawCircle(badgeLeft + badgeSize / 2, badgeTop + badgeSize / 2, badgeSize / 2, badgePaint)
			val inset = (BADGE_PADDING_DP * density * 2).toInt()
			drawable.setBounds(
				(badgeLeft + inset).toInt(),
				(badgeTop + inset).toInt(),
				(badgeLeft + badgeSize - inset).toInt(),
				(badgeTop + badgeSize - inset).toInt(),
			)
			drawable.draw(canvas)
		}

		val text = label ?: return
		// Without an icon the badge would be empty, so the name stands in.
		if (level < 0f && !(showLabel || (showBadge && drawable == null))) return
		if (badgeOffset > 0f) return
		drawLabel(canvas, text, badgeLeft, badgeTop, badgeSize, drawable != null && showBadge)
	}

	private fun drawWave(canvas: Canvas, localDownX: Float, localDownY: Float) {
		val length = waveLength
		val h = crest
		path.reset()

		if (side == SIDE_BOTTOM) {
			val bottom = height.toFloat()
			val start = localDownX - length / 2
			val drift = ((x - downX) / DRIFT_FACTOR).coerceIn(-length / 4, length / 4)
			val peak = start + length / 2 + drift
			path.moveTo(start, bottom)
			path.cubicTo(start + length * CONTROL_LENGTH, bottom - h * CONTROL_HEIGHT, peak - length * CONTROL_CREST, bottom - h, peak, bottom - h)
			path.cubicTo(peak + length * CONTROL_CREST, bottom - h, start + length * (1 - CONTROL_LENGTH), bottom - h * CONTROL_HEIGHT, start + length, bottom)
		} else {
			val edge = if (side == SIDE_LEFT) 0f else width.toFloat()
			val out = if (side == SIDE_LEFT) 1f else -1f
			val start = localDownY - length / 2
			val drift = ((y - downY) / DRIFT_FACTOR).coerceIn(-length / 4, length / 4)
			val peak = start + length / 2 + drift
			path.moveTo(edge, start)
			path.cubicTo(edge + out * h * CONTROL_HEIGHT, start + length * CONTROL_LENGTH, edge + out * h, peak - length * CONTROL_CREST, edge + out * h, peak)
			path.cubicTo(edge + out * h, peak + length * CONTROL_CREST, edge + out * h * CONTROL_HEIGHT, start + length * (1 - CONTROL_LENGTH), edge, start + length)
		}
		canvas.drawPath(path, wavePaint)
	}

	/**
	 * The diagonal sector the finger is in, as Edge Gestures shades it:
	 * the angle is measured the app's way, `atan2(dx, dy)` in degrees minus
	 * 90, and each edge has its two ranges.
	 */
	private fun drawRange(canvas: Canvas, localDownX: Float, localDownY: Float) {
		val angle = Math.toDegrees(atan2((x - downX).toDouble(), (y - downY).toDouble())) - 90
		val sector: Pair<Float, Float> = when (side) {
			SIDE_LEFT -> when {
				angle > 30 && angle < 65 -> -30f to -35f
				angle > -65 && angle < -30 -> 30f to 35f
				else -> return
			}
			SIDE_RIGHT -> when {
				angle > -245 && angle < -210 -> 210f to 35f
				angle > -150 && angle < -115 -> 150f to -35f
				else -> return
			}
			else -> when {
				angle > -240 && angle < -205 -> -120f to -35f
				angle > 25 && angle < 60 -> -60f to 35f
				else -> return
			}
		}
		val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
		rangePaint.color = if (night) 0x50FFFFFF else 0x50000000
		val radius = RANGE_RADIUS_DP * density
		rect.set(localDownX - radius, localDownY - radius, localDownX + radius, localDownY + radius)
		canvas.drawArc(rect, sector.first, sector.second, true, rangePaint)
	}

	private fun drawLabel(canvas: Canvas, text: String, badgeLeft: Float, badgeTop: Float, badgeSize: Float, besideBadge: Boolean) {
		val adjusting = level >= 0f
		// The level reads bigger and follows the finger, so the percentage
		// stays next to the thumb while it slides.
		labelPaint.textSize = (if (adjusting) 18 else 14) * density
		labelPaint.isFakeBoldText = adjusting
		val padding = 10 * density
		val textWidth = labelPaint.measureText(text)
		val barWidth = if (adjusting) 64 * density else 0f
		val boxWidth = textWidth + padding * 2 + barWidth
		val boxHeight = labelPaint.textSize + padding * 2
		val gap = (if (adjusting) 16 else 8) * density + if (besideBadge) badgeSize else crest

		getLocationOnScreen(location)
		val centerAlong = if (adjusting) {
			if (side == SIDE_BOTTOM) x - location[0] else y - location[1]
		} else {
			if (side == SIDE_BOTTOM) badgeLeft + badgeSize / 2 else badgeTop + badgeSize / 2
		}

		var left: Float
		var top: Float
		when (side) {
			SIDE_LEFT -> {
				left = gap
				top = centerAlong - boxHeight / 2
			}
			SIDE_RIGHT -> {
				left = width - gap - boxWidth
				top = centerAlong - boxHeight / 2
			}
			else -> {
				left = centerAlong - boxWidth / 2
				top = height - gap - boxHeight
			}
		}
		left = left.coerceIn(0f, maxOf(0f, width - boxWidth))
		top = top.coerceIn(0f, maxOf(0f, height - boxHeight))

		rect.set(left, top, left + boxWidth, top + boxHeight)
		canvas.drawRoundRect(rect, boxHeight / 2, boxHeight / 2, labelBackground)
		canvas.drawText(text, left + padding, top + padding - labelPaint.ascent() * 0.8f, labelPaint)

		if (level >= 0f) {
			val barLeft = left + padding + textWidth + 8 * density
			val barTop = top + boxHeight / 2 - 3 * density
			val trackWidth = 56 * density
			labelPaint.alpha = 70
			rect.set(barLeft, barTop, barLeft + trackWidth, barTop + 6 * density)
			canvas.drawRoundRect(rect, 3 * density, 3 * density, labelPaint)
			labelPaint.alpha = 255
			rect.set(barLeft, barTop, barLeft + trackWidth * level, barTop + 6 * density)
			canvas.drawRoundRect(rect, 3 * density, 3 * density, levelPaint)
		}
	}
}
