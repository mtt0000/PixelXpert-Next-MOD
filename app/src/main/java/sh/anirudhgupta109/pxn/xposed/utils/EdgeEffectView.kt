package sh.anirudhgupta109.pxn.xposed.utils

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import sh.anirudhgupta109.pxn.xposed.utils.EdgeGestureView.Companion.SIDE_BOTTOM
import sh.anirudhgupta109.pxn.xposed.utils.EdgeGestureView.Companion.SIDE_LEFT

/**
 * What a touch on an edge strip looks like: a wave that bulges out of the
 * edge under the finger and grows with the pull, the name of the action a
 * release would run next to it, and - during pull and slide - the level
 * being adjusted.
 *
 * It lives in a window of its own that takes no touches, laid over the whole
 * length of the edge, so the wave can follow the finger past the end of the
 * strip. [along] is in screen coordinates; the view converts it with its own
 * position on screen.
 */
@SuppressLint("ViewConstructor")
class EdgeEffectView(context: Context, val side: Int) : View(context) {

	var waveColor = Color.WHITE
		set(value) {
			field = value
			wavePaint.color = value
		}

	/** Largest bulge, in pixels. */
	var waveHeight = 0f

	/** Length of the bulge along the edge, in pixels. */
	var waveLength = 0f

	var showWave = true

	private val density = resources.displayMetrics.density
	private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG)
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
	private var along = 0f
	private var pull = 0f
	private var label: String? = null
	private var level = -1f

	fun update(along: Float, pull: Float, label: String?, active: Boolean) {
		this.active = active
		this.along = along
		this.pull = pull
		this.label = label
		if (!active) level = -1f
		invalidate()
	}

	/** Shows [label] and a [level] bar (0..1) while a value is being adjusted. */
	fun showLevel(label: String, level: Float) {
		this.active = true
		this.label = label
		this.level = level.coerceIn(0f, 1f)
		invalidate()
	}

	override fun onDraw(canvas: Canvas) {
		if (!active) return

		getLocationOnScreen(location)
		val center = if (side == SIDE_BOTTOM) along - location[0] else along - location[1]
		val amplitude = if (level >= 0f) waveHeight else minOf(pull, waveHeight)

		if (showWave && amplitude > 0f) drawWave(canvas, center, amplitude)

		val text = label ?: return
		val padding = 10 * density
		val textWidth = labelPaint.measureText(text)
		val boxWidth = textWidth + padding * 2 + if (level >= 0f) 72 * density else 0f
		val boxHeight = labelPaint.textSize + padding * 2
		val gap = amplitude + 12 * density

		val left: Float
		val top: Float
		when (side) {
			SIDE_LEFT -> {
				left = gap
				top = center - boxHeight / 2
			}
			SIDE_BOTTOM -> {
				left = (center - boxWidth / 2).coerceIn(0f, maxOf(0f, width - boxWidth))
				top = height - gap - boxHeight
			}
			else -> {
				left = width - gap - boxWidth
				top = center - boxHeight / 2
			}
		}
		val clampedTop = if (side == SIDE_BOTTOM) top else top.coerceIn(0f, maxOf(0f, height - boxHeight))

		rect.set(left, clampedTop, left + boxWidth, clampedTop + boxHeight)
		canvas.drawRoundRect(rect, boxHeight / 2, boxHeight / 2, labelBackground)
		canvas.drawText(text, left + padding, clampedTop + padding - labelPaint.ascent() * 0.8f, labelPaint)

		if (level >= 0f) {
			val barLeft = left + padding + textWidth + 8 * density
			val barWidth = 56 * density
			val barTop = clampedTop + boxHeight / 2 - 3 * density
			rect.set(barLeft, barTop, barLeft + barWidth, barTop + 6 * density)
			canvas.drawRoundRect(rect, 3 * density, 3 * density, labelPaint.apply { alpha = 70 })
			labelPaint.alpha = 255
			rect.set(barLeft, barTop, barLeft + barWidth * level, barTop + 6 * density)
			canvas.drawRoundRect(rect, 3 * density, 3 * density, levelPaint)
		}
	}

	private fun drawWave(canvas: Canvas, center: Float, amplitude: Float) {
		val half = waveLength / 2
		path.reset()
		when (side) {
			SIDE_LEFT -> {
				path.moveTo(0f, center - half)
				path.cubicTo(0f, center - half / 2, amplitude, center - half / 2, amplitude, center)
				path.cubicTo(amplitude, center + half / 2, 0f, center + half / 2, 0f, center + half)
			}
			SIDE_BOTTOM -> {
				val h = height.toFloat()
				path.moveTo(center - half, h)
				path.cubicTo(center - half / 2, h, center - half / 2, h - amplitude, center, h - amplitude)
				path.cubicTo(center + half / 2, h - amplitude, center + half / 2, h, center + half, h)
			}
			else -> {
				val w = width.toFloat()
				path.moveTo(w, center - half)
				path.cubicTo(w, center - half / 2, w - amplitude, center - half / 2, w - amplitude, center)
				path.cubicTo(w - amplitude, center + half / 2, w, center + half / 2, w, center + half)
			}
		}
		path.close()
		canvas.drawPath(path, wavePaint)
	}
}
