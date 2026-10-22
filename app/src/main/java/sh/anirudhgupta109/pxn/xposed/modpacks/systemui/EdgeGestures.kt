package sh.anirudhgupta109.pxn.xposed.modpacks.systemui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import de.robv.android.xposed.XposedHelpers.callMethod
import de.robv.android.xposed.XposedHelpers.callStaticMethod
import de.robv.android.xposed.XposedHelpers.getIntField
import de.robv.android.xposed.XposedHelpers.getStaticIntField
import de.robv.android.xposed.XposedHelpers.setIntField
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Proxy
import kotlin.math.roundToInt
import sh.anirudhgupta109.pxn.util.HiddenApps
import sh.anirudhgupta109.pxn.xposed.XPrefs.Xprefs
import sh.anirudhgupta109.pxn.xposed.XposedModPack
import sh.anirudhgupta109.pxn.xposed.annotations.SystemUIModPack
import sh.anirudhgupta109.pxn.xposed.utils.EdgeGestureView
import sh.anirudhgupta109.pxn.xposed.utils.EdgeGestureView.Companion.SIDE_LEFT
import sh.anirudhgupta109.pxn.xposed.utils.SystemUIActions
import sh.anirudhgupta109.pxn.xposed.utils.SystemUtils
import sh.anirudhgupta109.pxn.xposed.utils.reflection.ReflectedClass

/**
 * Edge gestures: touch strips on the left and right screen edges whose taps,
 * long presses and swipes run configurable actions, after the Edge Gestures
 * app - without the accessibility service or the overlay permission that app
 * needs, because SystemUI already holds everything involved.
 *
 * Each enabled edge is an [EdgeGestureView] in its own
 * `TYPE_APPLICATION_OVERLAY` window. That layer sits above apps but under
 * the IME, the notification shade and the keyguard, so the strips step aside
 * on their own whenever one of those is up. The windows are trusted overlays
 * (SystemUI may add internal system windows), so apps that refuse touches
 * through an obscuring window are not bothered by them. With "priority over
 * the system gesture" on, a strip claims its area from the back gesture
 * through an unrestricted gesture exclusion rect; with it off, the back
 * gesture steals swipes on the strip and only taps and long presses are
 * left to it.
 *
 * Length and position are fractions of the current screen height, re-applied
 * on every configuration change, so the strips follow rotation. A task stack
 * listener keeps track of the app in front to hide the strips on the
 * excluded apps and, optionally, on the home screen.
 */
@SystemUIModPack
class EdgeGestures(context: Context) : XposedModPack(context), EdgeGestureView.Listener {

	companion object {
		private const val START_DELAY = 5000L

		private val SIDE_PREFIXES = arrayOf("EdgeLeft", "EdgeRight")
		private val GESTURE_SUFFIXES = arrayOf("Tap", "DoubleTap", "LongPress", "SwipeIn", "SwipeUp", "SwipeDown")

		private var enabled = false
		private val sideEnabled = booleanArrayOf(true, true)
		private val lengthPercent = intArrayOf(50, 50)
		private val positionPercent = intArrayOf(50, 50)
		private val actions = Array(2) { IntArray(GESTURE_SUFFIXES.size) { SystemUIActions.NO_ACTION } }
		private var thicknessDp = 16
		private var swipeThresholdDp = 40
		private var hapticFeedback = true
		private var showHandle = false
		private var handleColor = Color.WHITE
		private var handleOpacity = 40
		private var priorityOverSystemGesture = true
		private var disableInLandscape = false
		private var disableOnHome = false
		private var excludedApps: Set<String> = emptySet()
		private var animateTorch = false
	}

	private val mainHandler = Handler(Looper.getMainLooper())
	private var windowContext: Context? = null
	private var windowManager: WindowManager? = null
	private val views = arrayOfNulls<EdgeGestureView>(2)
	private var foregroundPackage: String? = null
	private var homePackage: String? = null
	private var started = false

	override fun onPreferenceUpdated(vararg key: String?) {
		enabled = Xprefs.getBoolean("EdgeGesturesEnabled", false)

		for (side in SIDE_PREFIXES.indices) {
			val prefix = SIDE_PREFIXES[side]
			sideEnabled[side] = Xprefs.getBoolean("${prefix}Enabled", true)
			lengthPercent[side] = Xprefs.getSliderInt("${prefix}Length", 50)
			positionPercent[side] = Xprefs.getSliderInt("${prefix}Position", 50)
			for (gesture in GESTURE_SUFFIXES.indices) {
				val default = if (GESTURE_SUFFIXES[gesture] == "SwipeIn") SystemUIActions.ACTION_BACK.toString() else SystemUIActions.NO_ACTION.toString()
				actions[side][gesture] = SystemUIActions.parse(Xprefs.getString("$prefix${GESTURE_SUFFIXES[gesture]}", default))
			}
		}

		thicknessDp = Xprefs.getSliderInt("EdgeThickness", 16)
		swipeThresholdDp = Xprefs.getSliderInt("EdgeSwipeThreshold", 40)
		hapticFeedback = Xprefs.getBoolean("EdgeHapticFeedback", true)
		showHandle = Xprefs.getBoolean("EdgeShowHandle", false)
		handleColor = Xprefs.getInt("EdgeHandleColor", Color.WHITE)
		handleOpacity = Xprefs.getSliderInt("EdgeHandleOpacity", 40)
		priorityOverSystemGesture = Xprefs.getBoolean("EdgePriorityOverSystem", true)
		disableInLandscape = Xprefs.getBoolean("EdgeDisableInLandscape", false)
		disableOnHome = Xprefs.getBoolean("EdgeDisableOnHome", false)
		excludedApps = HiddenApps.parse(Xprefs.getString("EdgeExcludedApps", "")).toSet()
		animateTorch = Xprefs.getBoolean("AnimateFlashlight", false)

		mainHandler.post {
			if (started) {
				refreshForeground()
				applyState()
			}
		}
	}

	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		SystemUtils.CameraManager() //init CameraManager to listen to flash status

		// SystemUI is still bringing its own windows up at this point; the
		// strips can wait until it is settled.
		mainHandler.postDelayed({
			started = true
			registerTaskListener()
			refreshForeground()
			applyState()
		}, START_DELAY)
	}

	/** Adds, updates or removes each edge's window to match the preferences. */
	private fun applyState() {
		for (side in views.indices) {
			if (!enabled || !sideEnabled[side]) {
				removeEdge(side)
				continue
			}

			try {
				val view = views[side] ?: addEdge(side) ?: continue
				configure(view)
				windowManager!!.updateViewLayout(view, layoutParamsFor(side))
			} catch (t: Throwable) {
				log(t)
			}
		}
		updateVisibility()
	}

	private fun addEdge(side: Int): EdgeGestureView? {
		val context = windowContext ?: createWindowContext().also {
			windowContext = it
			windowManager = it.getSystemService(WindowManager::class.java)
		}

		val view = EdgeGestureView(context, side, this)
		configure(view)
		windowManager!!.addView(view, layoutParamsFor(side))
		views[side] = view
		return view
	}

	private fun removeEdge(side: Int) {
		val view = views[side] ?: return
		views[side] = null
		try {
			windowManager?.removeView(view)
		} catch (ignored: Throwable) {
		}
	}

	private fun createWindowContext(): Context {
		val display = mContext.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)!!
		return mContext
			.createDisplayContext(display)
			.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
	}

	private fun configure(view: EdgeGestureView) {
		val density = view.resources.displayMetrics.density
		val sideActions = actions[view.side]

		view.swipeThreshold = swipeThresholdDp * density
		view.waitForDoubleTap = sideActions[EdgeGestureView.GESTURE_DOUBLE_TAP] != SystemUIActions.NO_ACTION
		view.longPressEnabled = sideActions[EdgeGestureView.GESTURE_LONG_PRESS] != SystemUIActions.NO_ACTION
		view.showHandle = showHandle
		view.handleColor = Color.argb(
			(handleOpacity.coerceIn(0, 100) * 255 / 100f).roundToInt(),
			Color.red(handleColor),
			Color.green(handleColor),
			Color.blue(handleColor),
		)
		view.excludeSystemGesture = priorityOverSystemGesture
	}

	private fun layoutParamsFor(side: Int): WindowManager.LayoutParams {
		val context = windowContext!!
		val screen = windowManager!!.currentWindowMetrics.bounds
		val density = context.resources.displayMetrics.density

		val thickness = (thicknessDp * density).roundToInt().coerceAtLeast(1)
		val length = (screen.height() * lengthPercent[side] / 100f).roundToInt().coerceAtLeast(thickness)
		val top = ((screen.height() - length) * positionPercent[side] / 100f).roundToInt()

		@Suppress("DEPRECATION")
		return WindowManager.LayoutParams(
			thickness,
			length,
			WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
			WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
				WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
				WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
				WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
			PixelFormat.TRANSLUCENT,
		).apply {
			gravity = Gravity.TOP or (if (side == SIDE_LEFT) Gravity.LEFT else Gravity.RIGHT)
			y = top
			title = "PixelXpertEdgeGesture" + (if (side == SIDE_LEFT) "Left" else "Right")
			layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
			fitInsetsTypes = 0

			try {
				callMethod(this, "setTrustedOverlay")
			} catch (ignored: Throwable) {
			}

			// Apps are held to 200dp of exclusion per edge; a system window
			// may lift that, so a long strip keeps priority along its whole
			// length.
			if (priorityOverSystemGesture) {
				try {
					val flag = getStaticIntField(WindowManager.LayoutParams::class.java, "PRIVATE_FLAG_UNRESTRICTED_GESTURE_EXCLUSION")
					setIntField(this, "privateFlags", getIntField(this, "privateFlags") or flag)
				} catch (ignored: Throwable) {
				}
			}
		}
	}

	private fun updateVisibility() {
		val hidden = isHiddenNow()
		for (view in views) {
			view?.visibility = if (hidden) View.GONE else View.VISIBLE
		}
	}

	private fun isHiddenNow(): Boolean {
		val context = windowContext ?: return false

		if (disableInLandscape &&
			context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
		) {
			return true
		}

		val current = foregroundPackage ?: return false
		return current in excludedApps ||
			(disableOnHome && current == homePackage)
	}

	/**
	 * Only asks who is in front when an exclusion could act on the answer:
	 * with nothing excluded there is no reason for a binder call per task
	 * switch.
	 */
	private fun refreshForeground() {
		if (excludedApps.isEmpty() && !disableOnHome) {
			foregroundPackage = null
			return
		}

		foregroundPackage = SystemUIActions.foregroundPackage(mContext)
		if (disableOnHome) {
			homePackage = SystemUIActions.homePackage(mContext)
		}
	}

	/**
	 * SystemUI's own task stack registry takes a `TaskStackChangeListener`,
	 * an interface whose methods all have defaults; a proxy answers the few
	 * that mean "the app in front may have changed" and gives every other
	 * method its type's zero.
	 */
	private fun registerTaskListener() {
		try {
			val listenerClass = ReflectedClass.ofIfPossible("com.android.systemui.shared.system.TaskStackChangeListener").clazz ?: return
			val registryClass = ReflectedClass.ofIfPossible("com.android.systemui.shared.system.TaskStackChangeListeners").clazz ?: return

			val listener = Proxy.newProxyInstance(listenerClass.classLoader, arrayOf(listenerClass)) { proxy, method, args ->
				when (method.name) {
					"equals" -> proxy === args?.getOrNull(0)
					"hashCode" -> System.identityHashCode(proxy)
					"toString" -> "PixelXpert edge gestures task listener"

					"onTaskStackChanged", "onTaskMovedToFront", "onTaskFocusChanged" -> {
						mainHandler.post {
							refreshForeground()
							updateVisibility()
						}
						null
					}

					else -> when (method.returnType) {
						Boolean::class.javaPrimitiveType -> false
						Int::class.javaPrimitiveType -> 0
						Long::class.javaPrimitiveType -> 0L
						else -> null
					}
				}
			}

			callMethod(callStaticMethod(registryClass, "getInstance"), "registerTaskStackListener", listener)
		} catch (t: Throwable) {
			log(t)
		}
	}

	override fun onEdgeGesture(view: EdgeGestureView, gesture: Int) {
		val action = actions[view.side][gesture]
		if (action == SystemUIActions.NO_ACTION) return

		if (hapticFeedback) {
			SystemUtils.vibrate(VibrationEffect.EFFECT_CLICK, VibrationAttributes.USAGE_TOUCH)
		}
		SystemUIActions.run(mContext, action, animateTorch)
	}

	override fun onEdgeConfigurationChanged(view: EdgeGestureView) {
		// Both strips report the same change; one relayout is enough.
		mainHandler.removeCallbacks(relayoutRunnable)
		mainHandler.post(relayoutRunnable)
	}

	private val relayoutRunnable = Runnable { applyState() }
}
