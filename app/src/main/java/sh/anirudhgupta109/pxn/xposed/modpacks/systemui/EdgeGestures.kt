package sh.anirudhgupta109.pxn.xposed.modpacks.systemui

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
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
import sh.anirudhgupta109.pxn.Constants
import sh.anirudhgupta109.pxn.R
import sh.anirudhgupta109.pxn.util.HiddenApps
import sh.anirudhgupta109.pxn.xposed.XPLauncher
import sh.anirudhgupta109.pxn.xposed.XPrefs.Xprefs
import sh.anirudhgupta109.pxn.xposed.XposedModPack
import sh.anirudhgupta109.pxn.xposed.annotations.SystemUIModPack
import sh.anirudhgupta109.pxn.xposed.utils.EdgeEffectView
import sh.anirudhgupta109.pxn.xposed.utils.EdgeGestureView
import sh.anirudhgupta109.pxn.xposed.utils.EdgeGestureView.Companion.GESTURE_COUNT
import sh.anirudhgupta109.pxn.xposed.utils.EdgeGestureView.Companion.GESTURE_KEYS
import sh.anirudhgupta109.pxn.xposed.utils.EdgeGestureView.Companion.GESTURE_SWIPE_IN
import sh.anirudhgupta109.pxn.xposed.utils.EdgeGestureView.Companion.NO_GESTURE
import sh.anirudhgupta109.pxn.xposed.utils.EdgeGestureView.Companion.SIDE_BOTTOM
import sh.anirudhgupta109.pxn.xposed.utils.EdgeGestureView.Companion.SIDE_LEFT
import sh.anirudhgupta109.pxn.xposed.utils.SystemUIActions
import sh.anirudhgupta109.pxn.xposed.utils.SystemUtils
import sh.anirudhgupta109.pxn.xposed.utils.reflection.ReflectedClass

/**
 * Edge gestures: touch strips on the left, right and bottom screen edges
 * whose taps, presses and swipes run configurable actions - the Edge Gestures
 * app and Samsung's One Hand Operation+ in one, without an accessibility
 * service or the overlay permission, because SystemUI already holds
 * everything involved.
 *
 * Each enabled edge is an [EdgeGestureView] in its own
 * `TYPE_APPLICATION_OVERLAY` window: above apps, under the notification shade
 * and the keyguard. The windows are trusted overlays (SystemUI may add
 * internal system windows), so apps that refuse touches through an obscuring
 * window are not bothered by them. "Priority over the system gesture" claims
 * the strips' area from the back gesture through an unrestricted exclusion
 * rect; with it off the back gesture steals swipes on the side strips.
 *
 * The IME does not sit above app overlays (it is layered just above the app
 * it types into), so the pack follows the keyboard through the WM shell's
 * `DisplayImeController` and, depending on the preference, cuts the strips
 * off where the keyboard starts ("underneath"), moves them above it, hides
 * them, or leaves them over it.
 *
 * Next to every strip an [EdgeEffectView] in a touch-less window draws the
 * wave under the finger, the name of the action a release would run, and the
 * level during pull and slide.
 *
 * The same action runner serves the launcher's bottom-corner swipes, which
 * send [Constants.ACTION_RUN_GESTURE_ACTION] for anything they cannot do
 * in the launcher process; the receiver only takes it from the launcher.
 */
@SystemUIModPack
class EdgeGestures(context: Context) : XposedModPack(context), EdgeGestureView.Listener {

	companion object {
		private const val START_DELAY = 5000L
		private const val SIDES = 3

		private val SIDE_PREFIXES = arrayOf("EdgeLeft", "EdgeRight", "EdgeBottom")

		private const val ORIENTATION_PORTRAIT = 1
		private const val ORIENTATION_LANDSCAPE = 2
		private const val ORIENTATION_BOTH = 3

		// Same numbers as the Edge Gestures app, so its backups import as is.
		const val KEYBOARD_SHIFT = 1
		const val KEYBOARD_UNDERNEATH = 2
		const val KEYBOARD_OVERLAP = 3
		const val KEYBOARD_HIDE = 4

		const val ADJUST_NONE = 0
		const val ADJUST_BRIGHTNESS = 1
		const val ADJUST_MEDIA = 2
		const val ADJUST_SYSTEM = 5
		const val ADJUST_NOTIFICATION = 6
		const val ADJUST_RINGTONE = 7

		/** Along travel, as a share of the screen, that covers the whole range. */
		private const val ADJUST_RANGE = 0.6f

		private const val DISABLE_SHORT = 5000L

		private var enabled = false
		private val sideEnabled = booleanArrayOf(true, true, false)
		private val orientation = intArrayOf(ORIENTATION_BOTH, ORIENTATION_BOTH, ORIENTATION_BOTH)
		private val lengthPercent = intArrayOf(50, 50, 60)
		private val positionPercent = intArrayOf(50, 50, 50)
		private var bottomOffsetDp = 0
		private val adjust = IntArray(SIDES)
		private val actions = Array(SIDES) { Array(GESTURE_COUNT) { "" } }
		private val actionLabels = Array(SIDES) { arrayOfNulls<String>(GESTURE_COUNT) }
		private var thicknessDp = 16
		private var swipeThresholdDp = 40
		private var longSwipeThresholdDp = 160
		private var holdTimeout = 500
		private var hapticFeedback = true
		private var soundEffect = false
		private var showHandle = false
		private var handleColor = Color.WHITE
		private var handleOpacity = 40
		private var visualEffect = true
		private var effectColor = 0xA0303030.toInt()
		private var waveHeightDp = 24
		private var waveLengthDp = 160
		private var showActionLabel = true
		private var priorityOverSystemGesture = true
		private var keyboardBehavior = KEYBOARD_UNDERNEATH
		private var disableOnHome = false
		private var excludedApps: Set<String> = emptySet()
		private var animateTorch = false
	}

	private val mainHandler = Handler(Looper.getMainLooper())
	private var windowContext: Context? = null
	private var windowManager: WindowManager? = null
	private val views = arrayOfNulls<EdgeGestureView>(SIDES)
	private val effects = arrayOfNulls<EdgeEffectView>(SIDES)
	private var foregroundPackage: String? = null
	private var homePackage: String? = null
	private var started = false

	private var imeVisible = false
	private var imeTop = -1
	private var imeFloating = false

	private var disabledUntil = 0L
	private var disabledUntilTouch = false
	private var touchWatcher: View? = null
	private var blackout: View? = null
	private var keepAwake: View? = null

	private var adjustLevel = 0f

	override fun onPreferenceUpdated(vararg key: String?) {
		enabled = Xprefs.getBoolean("EdgeGesturesEnabled", false)

		// Phase 1 had one landscape switch for both edges.
		val legacyOrientation = if (Xprefs.getBoolean("EdgeDisableInLandscape", false)) ORIENTATION_PORTRAIT else ORIENTATION_BOTH

		for (side in 0 until SIDES) {
			val prefix = SIDE_PREFIXES[side]
			sideEnabled[side] = Xprefs.getBoolean("${prefix}Enabled", side != SIDE_BOTTOM)
			orientation[side] = Xprefs.getString("${prefix}Orientation", legacyOrientation.toString())!!.toIntOrNull() ?: ORIENTATION_BOTH
			lengthPercent[side] = Xprefs.getSliderInt("${prefix}Length", if (side == SIDE_BOTTOM) 60 else 50)
			positionPercent[side] = Xprefs.getSliderInt("${prefix}Position", 50)
			adjust[side] = Xprefs.getString("${prefix}Adjust", "0")!!.toIntOrNull() ?: ADJUST_NONE
			for (gesture in 0 until GESTURE_COUNT) {
				val default = if (gesture == GESTURE_SWIPE_IN && side != SIDE_BOTTOM) SystemUIActions.ACTION_BACK.toString() else ""
				actions[side][gesture] = Xprefs.getString("$prefix${GESTURE_KEYS[gesture]}", default) ?: ""
				actionLabels[side][gesture] = Xprefs.getString("$prefix${GESTURE_KEYS[gesture]}Label", null)
			}
		}
		bottomOffsetDp = Xprefs.getSliderInt("EdgeBottomOffset", 0)

		thicknessDp = Xprefs.getSliderInt("EdgeThickness", 16)
		swipeThresholdDp = Xprefs.getSliderInt("EdgeSwipeThreshold", 40)
		longSwipeThresholdDp = Xprefs.getSliderInt("EdgeLongSwipeThreshold", 160)
		holdTimeout = Xprefs.getSliderInt("EdgeHoldTimeout", 500)
		hapticFeedback = Xprefs.getBoolean("EdgeHapticFeedback", true)
		soundEffect = Xprefs.getBoolean("EdgeSoundEffect", false)
		showHandle = Xprefs.getBoolean("EdgeShowHandle", false)
		handleColor = Xprefs.getInt("EdgeHandleColor", Color.WHITE)
		handleOpacity = Xprefs.getSliderInt("EdgeHandleOpacity", 40)
		visualEffect = Xprefs.getBoolean("EdgeVisualEffect", true)
		effectColor = Xprefs.getInt("EdgeEffectColor", 0xA0303030.toInt())
		waveHeightDp = Xprefs.getSliderInt("EdgeWaveHeight", 24)
		waveLengthDp = Xprefs.getSliderInt("EdgeWaveLength", 160)
		showActionLabel = Xprefs.getBoolean("EdgeShowActionLabel", true)
		priorityOverSystemGesture = Xprefs.getBoolean("EdgePriorityOverSystem", true)
		keyboardBehavior = Xprefs.getString("EdgeKeyboardBehavior", KEYBOARD_UNDERNEATH.toString())!!.toIntOrNull() ?: KEYBOARD_UNDERNEATH
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

		hookWmShell()
		hookKeyboard()
		registerActionReceiver()

		// SystemUI is still bringing its own windows up at this point; the
		// strips can wait until it is settled.
		mainHandler.postDelayed({
			started = true
			registerTaskListener()
			refreshForeground()
			applyState()
		}, START_DELAY)
	}

	// ------------------------------------------------------------ windows

	/** Adds, updates or removes each edge's windows to match the preferences. */
	private fun applyState() {
		for (side in 0 until SIDES) {
			if (!enabled || !sideEnabled[side]) {
				removeEdge(side)
				continue
			}

			try {
				val view = views[side] ?: addEdge(side)
				configure(view)
				windowManager!!.updateViewLayout(view, stripParams(side))
				updateEffect(side)
			} catch (t: Throwable) {
				log(t)
			}
		}
		updateVisibility()
	}

	private fun windowContext(): Context =
		windowContext ?: run {
			val display = mContext.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)!!
			mContext
				.createDisplayContext(display)
				.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
				.also {
					windowContext = it
					windowManager = it.getSystemService(WindowManager::class.java)
				}
		}

	private fun addEdge(side: Int): EdgeGestureView {
		val view = EdgeGestureView(windowContext(), side, this)
		configure(view)
		windowManager!!.addView(view, stripParams(side))
		views[side] = view
		return view
	}

	private fun removeEdge(side: Int) {
		views[side]?.let { removeWindow(it) }
		views[side] = null
		effects[side]?.let { removeWindow(it) }
		effects[side] = null
	}

	private fun removeWindow(view: View) {
		try {
			windowManager?.removeView(view)
		} catch (ignored: Throwable) {
		}
	}

	private fun configure(view: EdgeGestureView) {
		val density = view.resources.displayMetrics.density
		val side = view.side

		view.swipeThreshold = swipeThresholdDp * density
		view.longSwipeThreshold = longSwipeThresholdDp * density
		view.holdTimeout = holdTimeout.toLong()
		for (gesture in 0 until GESTURE_COUNT) {
			view.bound[gesture] = SystemUIActions.isBound(actions[side][gesture])
		}
		view.adjustEnabled = adjust[side] != ADJUST_NONE
		view.showHandle = showHandle
		view.handleColor = withOpacity(handleColor, handleOpacity)
		view.excludeSystemGesture = priorityOverSystemGesture
	}

	private fun withOpacity(color: Int, percent: Int): Int =
		Color.argb(
			(percent.coerceIn(0, 100) * 255 / 100f).roundToInt(),
			Color.red(color),
			Color.green(color),
			Color.blue(color),
		)

	/** The strip's rectangle on screen right now: left, top, width, height. */
	private fun stripBounds(side: Int): IntArray {
		val context = windowContext()
		val screen = windowManager!!.currentWindowMetrics.bounds
		val density = context.resources.displayMetrics.density
		val thickness = (thicknessDp * density).roundToInt().coerceAtLeast(1)

		if (side == SIDE_BOTTOM) {
			val length = (screen.width() * lengthPercent[side] / 100f).roundToInt().coerceAtLeast(thickness)
			val left = ((screen.width() - length) * positionPercent[side] / 100f).roundToInt()
			var bottom = screen.height() - (bottomOffsetDp * density).roundToInt()
			if (keyboardApplies() && keyboardBehavior == KEYBOARD_SHIFT && imeTop in 1 until bottom) {
				bottom = imeTop
			}
			return intArrayOf(left, bottom - thickness, length, thickness)
		}

		var length = (screen.height() * lengthPercent[side] / 100f).roundToInt().coerceAtLeast(thickness)
		var top = ((screen.height() - length) * positionPercent[side] / 100f).roundToInt()

		if (keyboardApplies() && imeTop in 1 until top + length) {
			when (keyboardBehavior) {
				KEYBOARD_UNDERNEATH -> length = (imeTop - top).coerceAtLeast(0)
				KEYBOARD_SHIFT -> top = (imeTop - length).coerceAtLeast(0)
			}
		}
		return intArrayOf(0, top, thickness, length)
	}

	private fun keyboardApplies(): Boolean = imeVisible && !imeFloating

	private fun stripParams(side: Int): WindowManager.LayoutParams {
		val bounds = stripBounds(side)
		return overlayParams(bounds[2], bounds[3].coerceAtLeast(1), touchable = true).apply {
			gravity = Gravity.TOP or when (side) {
				SIDE_LEFT -> Gravity.LEFT
				SIDE_BOTTOM -> Gravity.LEFT
				else -> Gravity.RIGHT
			}
			x = bounds[0]
			y = bounds[1]
			title = "PixelXpertEdgeGesture" + SIDE_PREFIXES[side]

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

	private fun overlayParams(width: Int, height: Int, touchable: Boolean, type: Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY): WindowManager.LayoutParams {
		var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
			WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
			WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
			WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
		if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE

		return WindowManager.LayoutParams(width, height, type, flags, PixelFormat.TRANSLUCENT).apply {
			layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
			fitInsetsTypes = 0
			try {
				callMethod(this, "setTrustedOverlay")
			} catch (ignored: Throwable) {
			}
		}
	}

	/** The touch-less window the wave, the action name and the level are drawn in. */
	private fun updateEffect(side: Int) {
		val wanted = visualEffect || showActionLabel || adjust[side] != ADJUST_NONE
		if (!wanted) {
			effects[side]?.let { removeWindow(it) }
			effects[side] = null
			return
		}

		val context = windowContext()
		val density = context.resources.displayMetrics.density
		val view = effects[side] ?: EdgeEffectView(context, side).also { effects[side] = it }

		view.showWave = visualEffect
		view.waveColor = effectColor
		view.waveHeight = waveHeightDp * density
		view.waveLength = waveLengthDp * density

		val depth = (280 * density).roundToInt()
		val screen = windowManager!!.currentWindowMetrics.bounds
		val params = if (side == SIDE_BOTTOM) {
			val strip = stripBounds(side)
			overlayParams(screen.width(), depth, touchable = false).apply {
				gravity = Gravity.TOP or Gravity.LEFT
				y = (strip[1] + strip[3] - depth).coerceAtLeast(0)
			}
		} else {
			overlayParams(depth, screen.height(), touchable = false).apply {
				gravity = Gravity.TOP or (if (side == SIDE_LEFT) Gravity.LEFT else Gravity.RIGHT)
			}
		}
		params.title = "PixelXpertEdgeEffect" + SIDE_PREFIXES[side]

		if (view.parent == null) windowManager!!.addView(view, params)
		else windowManager!!.updateViewLayout(view, params)
	}

	private fun updateVisibility() {
		val hidden = isHiddenNow()
		for (side in 0 until SIDES) {
			val view = views[side] ?: continue
			val sideHidden = hidden || !orientationAllows(side) || isCoveredByKeyboard(side)
			view.visibility = if (sideHidden) View.GONE else View.VISIBLE
			effects[side]?.visibility = view.visibility
		}
	}

	private fun orientationAllows(side: Int): Boolean {
		val landscape = windowContext().resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
		return when (orientation[side]) {
			ORIENTATION_PORTRAIT -> !landscape
			ORIENTATION_LANDSCAPE -> landscape
			else -> true
		}
	}

	private fun isCoveredByKeyboard(side: Int): Boolean {
		if (!keyboardApplies()) return false
		return when (keyboardBehavior) {
			KEYBOARD_HIDE -> true
			// Unknown position: the strip cannot be cut to fit, so it steps aside.
			KEYBOARD_UNDERNEATH, KEYBOARD_SHIFT -> imeTop <= 0 ||
				(side == SIDE_BOTTOM && keyboardBehavior == KEYBOARD_UNDERNEATH) ||
				stripBounds(side)[3] < thicknessDp * windowContext().resources.displayMetrics.density
			else -> false
		}
	}

	private fun isHiddenNow(): Boolean {
		if (disabledUntilTouch || SystemClock.uptimeMillis() < disabledUntil) return true

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

	// ------------------------------------------------------------ hooks

	/** The WM shell controllers the split screen and one-handed actions drive. */
	private fun hookWmShell() {
		ReflectedClass.ofIfPossible("com.android.wm.shell.splitscreen.SplitScreenController")
			.afterConstruction()
			.run { param -> SystemUIActions.splitScreenController = param.thisObject }

		ReflectedClass.ofIfPossible("com.android.wm.shell.onehanded.OneHandedController")
			.afterConstruction()
			.run { param -> SystemUIActions.oneHandedController = param.thisObject }
	}

	/**
	 * `DisplayImeController` tells its position processors where the IME is
	 * going (`dispatchStartPositioning(displayId, hiddenTop, shownTop, show,
	 * isFloating, t)`) and whether it ended up shown; `CommandQueue`'s IME
	 * status is the fallback that only knows shown or hidden. Arguments are
	 * picked by type, because their order has shifted between releases.
	 */
	private fun hookKeyboard() {
		val imeController = ReflectedClass.ofIfPossible("com.android.wm.shell.common.DisplayImeController")

		imeController
			.before("dispatchStartPositioning")
			.run { param ->
				val ints = param.args.filterIsInstance<Int>()
				val booleans = param.args.filterIsInstance<Boolean>()
				if (ints.size < 3 || ints[0] != Display.DEFAULT_DISPLAY || booleans.isEmpty()) return@run
				onImeChanged(booleans[0], if (booleans[0]) ints[2] else -1, booleans.getOrElse(1) { false })
			}

		imeController
			.before("dispatchVisibilityChanged")
			.run { param ->
				val ints = param.args.filterIsInstance<Int>()
				val booleans = param.args.filterIsInstance<Boolean>()
				if (ints.isEmpty() || ints[0] != Display.DEFAULT_DISPLAY || booleans.isEmpty()) return@run
				if (!booleans[0]) onImeChanged(false, -1, false)
				else if (!imeVisible) onImeChanged(true, imeTop, imeFloating)
			}

		ReflectedClass.ofIfPossible("com.android.systemui.statusbar.CommandQueue")
			.before("setImeWindowStatus")
			.run { param ->
				val ints = param.args.filterIsInstance<Int>()
				if (ints.size < 2 || ints[0] != Display.DEFAULT_DISPLAY) return@run
				// InputMethodService.IME_VISIBLE
				val visible = (ints[1] and 2) != 0
				if (visible != imeVisible) onImeChanged(visible, if (visible) imeTop else -1, imeFloating)
			}
	}

	private fun onImeChanged(visible: Boolean, top: Int, floating: Boolean) {
		mainHandler.post {
			if (imeVisible == visible && imeTop == top && imeFloating == floating) return@post
			imeVisible = visible
			imeTop = top
			imeFloating = floating
			if (started) applyState()
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

	/**
	 * Gesture actions the launcher forwards. Only the launcher may ask:
	 * it sends the broadcast with its identity shared, and anything that
	 * does not name it is dropped.
	 */
	@SuppressLint("WrongConstant")
	private fun registerActionReceiver() {
		val receiver = object : BroadcastReceiver() {
			override fun onReceive(context: Context, intent: Intent) {
				val sender = try {
					sentFromPackage
				} catch (ignored: Throwable) {
					null
				}
				if (sender != Constants.LAUNCHER_PACKAGE) return

				val value = intent.getStringExtra(Constants.EXTRA_GESTURE_ACTION) ?: return
				mainHandler.post { runAction(value, -1f, -1f) }
			}
		}
		try {
			mContext.registerReceiver(receiver, IntentFilter(Constants.ACTION_RUN_GESTURE_ACTION), Context.RECEIVER_EXPORTED)
		} catch (t: Throwable) {
			log(t)
		}
	}

	// ------------------------------------------------------------ gestures

	override fun onEdgeGesture(view: EdgeGestureView, gesture: Int, rawX: Float, rawY: Float) {
		val value = actions[view.side][gesture]
		if (!SystemUIActions.isBound(value)) return

		if (hapticFeedback) {
			SystemUtils.vibrate(VibrationEffect.EFFECT_CLICK, VibrationAttributes.USAGE_TOUCH)
		}
		if (soundEffect) {
			mContext.getSystemService(AudioManager::class.java).playSoundEffect(AudioManager.FX_KEY_CLICK)
		}
		runAction(value, rawX, rawY)
	}

	private fun runAction(value: String, rawX: Float, rawY: Float) {
		if (SystemUIActions.run(mContext, value, animateTorch)) return

		when (SystemUIActions.code(value)) {
			SystemUIActions.ACTION_TAP_THROUGH -> if (rawX >= 0f) tapThrough(rawX, rawY)
			SystemUIActions.ACTION_SCROLL_TOP -> scroll(towardsTop = true)
			SystemUIActions.ACTION_SCROLL_BOTTOM -> scroll(towardsTop = false)
			SystemUIActions.ACTION_DISABLE_5S -> disableFor(DISABLE_SHORT)
			SystemUIActions.ACTION_DISABLE_UNTIL_TOUCH -> disableUntilNextTouch()
			SystemUIActions.ACTION_BLACKOUT -> startBlackout()
			SystemUIActions.ACTION_KEEP_AWAKE -> toggleKeepAwake()
		}
	}

	override fun onEdgeTrack(view: EdgeGestureView, along: Float, pull: Float, candidate: Int, active: Boolean) {
		val effect = effects[view.side] ?: return
		val label = if (showActionLabel && candidate != NO_GESTURE) labelOf(view.side, candidate) else null
		effect.update(along, pull, label, active)
	}

	private fun labelOf(side: Int, gesture: Int): String? {
		val value = actions[side][gesture].trim()
		return when {
			value.startsWith(SystemUIActions.APP_PREFIX) -> try {
				val pm = mContext.packageManager
				pm.getApplicationLabel(pm.getApplicationInfo(value.removePrefix(SystemUIActions.APP_PREFIX), 0)).toString()
			} catch (ignored: Throwable) {
				null
			}
			value.startsWith(SystemUIActions.INTENT_PREFIX) -> actionLabels[side][gesture]
			else -> try {
				val resources = XPLauncher.moduleResources!!
				val index = resources.getStringArray(R.array.edge_action_codes).indexOf(value)
				if (index < 0) null else resources.getStringArray(R.array.edge_action_titles)[index]
			} catch (ignored: Throwable) {
				null
			}
		}
	}

	// ------------------------------------------------------------ pull and slide

	override fun onEdgeAdjustStart(view: EdgeGestureView) {
		adjustLevel = readLevel(adjust[view.side])
		showLevel(view.side)
	}

	override fun onEdgeAdjust(view: EdgeGestureView, delta: Float) {
		val screen = windowManager!!.currentWindowMetrics.bounds
		val range = (if (view.side == SIDE_BOTTOM) screen.width() else screen.height()) * ADJUST_RANGE
		// Upwards raises on the side edges, rightwards on the bottom one.
		val change = if (view.side == SIDE_BOTTOM) delta / range else -delta / range
		adjustLevel = (adjustLevel + change).coerceIn(0f, 1f)
		writeLevel(adjust[view.side], adjustLevel)
		showLevel(view.side)
	}

	override fun onEdgeAdjustEnd(view: EdgeGestureView) {
		if (adjust[view.side] == ADJUST_BRIGHTNESS) writeLevel(ADJUST_BRIGHTNESS, adjustLevel, persist = true)
		effects[view.side]?.update(0f, 0f, null, false)
	}

	private fun showLevel(side: Int) {
		val name = when (adjust[side]) {
			ADJUST_BRIGHTNESS -> R.string.edge_adjust_brightness
			ADJUST_MEDIA -> R.string.edge_adjust_media
			ADJUST_RINGTONE -> R.string.edge_adjust_ringtone
			ADJUST_NOTIFICATION -> R.string.edge_adjust_notification
			else -> R.string.edge_adjust_system
		}
		effects[side]?.showLevel(
			SystemUIActions.string(name) + " " + (adjustLevel * 100).roundToInt() + "%",
			adjustLevel,
		)
	}

	private fun streamOf(kind: Int): Int = when (kind) {
		ADJUST_MEDIA -> AudioManager.STREAM_MUSIC
		ADJUST_RINGTONE -> AudioManager.STREAM_RING
		ADJUST_NOTIFICATION -> AudioManager.STREAM_NOTIFICATION
		else -> AudioManager.STREAM_SYSTEM
	}

	/**
	 * Brightness is handled on SystemUI's own perceptual scale - the one its
	 * slider uses - so the level moves the way the slider does; the volumes
	 * are plain steps.
	 */
	private fun readLevel(kind: Int): Float = try {
		if (kind == ADJUST_BRIGHTNESS) {
			val displayManager = mContext.getSystemService(DisplayManager::class.java)
			val linear = callMethod(displayManager, "getBrightness", Display.DEFAULT_DISPLAY) as Float
			linearToPerceptual(linear)
		} else {
			val audioManager = mContext.getSystemService(AudioManager::class.java)
			val stream = streamOf(kind)
			val min = audioManager.getStreamMinVolume(stream)
			val max = audioManager.getStreamMaxVolume(stream)
			(audioManager.getStreamVolume(stream) - min) / (max - min).coerceAtLeast(1).toFloat()
		}
	} catch (ignored: Throwable) {
		0.5f
	}

	private fun writeLevel(kind: Int, level: Float, persist: Boolean = false) {
		try {
			if (kind == ADJUST_BRIGHTNESS) {
				val displayManager = mContext.getSystemService(DisplayManager::class.java)
				val linear = perceptualToLinear(level)
				if (Settings.System.getInt(mContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == 1) {
					// A manual pick ends adaptive brightness, as the slider's does.
					Settings.System.putInt(mContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0)
				}
				if (persist) callMethod(displayManager, "setBrightness", Display.DEFAULT_DISPLAY, linear)
				else callMethod(displayManager, "setTemporaryBrightness", Display.DEFAULT_DISPLAY, linear)
			} else {
				val audioManager = mContext.getSystemService(AudioManager::class.java)
				val stream = streamOf(kind)
				val min = audioManager.getStreamMinVolume(stream)
				val max = audioManager.getStreamMaxVolume(stream)
				val index = (min + (max - min) * level).roundToInt()
				if (index != audioManager.getStreamVolume(stream)) {
					audioManager.setStreamVolume(stream, index, 0)
				}
			}
		} catch (ignored: Throwable) {
		}
	}

	private fun brightnessUtils(): Class<*>? =
		ReflectedClass.ofIfPossible("com.android.settingslib.display.BrightnessUtils").clazz

	private fun linearToPerceptual(linear: Float): Float = try {
		(callStaticMethod(brightnessUtils(), "convertLinearToGammaFloat", linear, 0f, 1f) as Int) / 65535f
	} catch (ignored: Throwable) {
		Math.sqrt(linear.toDouble()).toFloat()
	}

	private fun perceptualToLinear(level: Float): Float = try {
		callStaticMethod(brightnessUtils(), "convertGammaToLinearFloat", (level * 65535).roundToInt(), 0f, 1f) as Float
	} catch (ignored: Throwable) {
		level * level
	}.coerceIn(0f, 1f)

	// ------------------------------------------------------------ edge-only actions

	/**
	 * Hands a tap on the strip to whatever is under it: the strips stop
	 * taking touches, a tap is injected at the same point, and they come
	 * back once it has been delivered.
	 */
	private fun tapThrough(x: Float, y: Float) {
		setStripsTouchable(false)
		mainHandler.postDelayed({
			Thread {
				val downTime = SystemClock.uptimeMillis()
				SystemUIActions.inject(mContext, SystemUIActions.touchEvent(downTime, MotionEvent.ACTION_DOWN, x, y))
				SystemUtils.threadSleep(40)
				SystemUIActions.inject(mContext, SystemUIActions.touchEvent(downTime, MotionEvent.ACTION_UP, x, y))
				mainHandler.postDelayed({ setStripsTouchable(true) }, 150)
			}.start()
		}, 80)
	}

	private fun setStripsTouchable(touchable: Boolean) {
		for (side in 0 until SIDES) {
			val view = views[side] ?: continue
			try {
				val params = view.layoutParams as WindowManager.LayoutParams
				params.flags =
					if (touchable) params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
					else params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
				windowManager!!.updateViewLayout(view, params)
			} catch (ignored: Throwable) {
			}
		}
	}

	/**
	 * Without an accessibility service there is no view tree to scroll, so
	 * the action flicks the app in front a few times, mid-screen and away
	 * from the strips.
	 */
	private fun scroll(towardsTop: Boolean) {
		val screen = windowManager?.currentWindowMetrics?.bounds ?: return
		val x = screen.width() / 2f
		val high = screen.height() * 0.3f
		val low = screen.height() * 0.8f
		Thread {
			repeat(5) {
				if (towardsTop) SystemUIActions.injectSwipe(mContext, x, high, x, low, 60)
				else SystemUIActions.injectSwipe(mContext, x, low, x, high, 60)
				SystemUtils.threadSleep(70)
			}
		}.start()
	}

	private fun disableFor(millis: Long) {
		disabledUntil = SystemClock.uptimeMillis() + millis
		updateVisibility()
		mainHandler.postDelayed({ updateVisibility() }, millis + 50)
	}

	/**
	 * Hides the strips until the finger lands anywhere else: a one-pixel
	 * window that watches outside touches is the only thing left on screen
	 * listening, and it goes with the first touch it hears about.
	 */
	@SuppressLint("ClickableViewAccessibility")
	private fun disableUntilNextTouch() {
		if (touchWatcher != null) return
		disabledUntilTouch = true
		updateVisibility()

		val watcher = View(windowContext())
		watcher.setOnTouchListener { _, _ ->
			removeTouchWatcher()
			true
		}
		val params = overlayParams(1, 1, touchable = true).apply {
			flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
			gravity = Gravity.TOP or Gravity.LEFT
			title = "PixelXpertEdgeTouchWatcher"
		}
		try {
			windowManager!!.addView(watcher, params)
			touchWatcher = watcher
		} catch (t: Throwable) {
			disabledUntilTouch = false
			updateVisibility()
		}
	}

	private fun removeTouchWatcher() {
		touchWatcher?.let { removeWindow(it) }
		touchWatcher = null
		disabledUntilTouch = false
		mainHandler.post { updateVisibility() }
	}

	/**
	 * A black screen that keeps the device awake, at the lowest backlight -
	 * for music or navigation with the display out of the way. Double tap
	 * anywhere ends it.
	 */
	@SuppressLint("ClickableViewAccessibility")
	private fun startBlackout() {
		if (blackout != null) return
		val context = windowContext()
		val view = View(context)
		view.setBackgroundColor(Color.BLACK)

		var lastTap = 0L
		view.setOnTouchListener { _, event ->
			if (event.actionMasked == MotionEvent.ACTION_UP) {
				val now = SystemClock.uptimeMillis()
				if (now - lastTap < 400) endBlackout() else lastTap = now
			}
			true
		}

		val screen = windowManager!!.maximumWindowMetrics.bounds
		val size = maxOf(screen.width(), screen.height())
		val params = overlayParams(size, size, touchable = true, type = WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL).apply {
			flags = (flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) and
				WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL.inv()
			gravity = Gravity.TOP or Gravity.LEFT
			format = PixelFormat.OPAQUE
			screenBrightness = 0.01f
			title = "PixelXpertEdgeBlackout"
		}
		try {
			windowManager!!.addView(view, params)
			blackout = view
			SystemUIActions.toast(mContext, SystemUIActions.string(R.string.edge_blackout_hint))
		} catch (t: Throwable) {
			log(t)
		}
	}

	private fun endBlackout() {
		blackout?.let { removeWindow(it) }
		blackout = null
	}

	private fun toggleKeepAwake() {
		val current = keepAwake
		if (current != null) {
			removeWindow(current)
			keepAwake = null
			SystemUIActions.toast(mContext, SystemUIActions.string(R.string.edge_keep_awake_off))
			return
		}

		val view = View(windowContext())
		val params = overlayParams(1, 1, touchable = false).apply {
			flags = flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
			gravity = Gravity.TOP or Gravity.LEFT
			title = "PixelXpertEdgeKeepAwake"
		}
		try {
			windowManager!!.addView(view, params)
			keepAwake = view
			SystemUIActions.toast(mContext, SystemUIActions.string(R.string.edge_keep_awake_on))
		} catch (t: Throwable) {
			log(t)
		}
	}

	override fun onEdgeConfigurationChanged(view: EdgeGestureView) {
		// Every strip reports the same change; one relayout is enough.
		mainHandler.removeCallbacks(relayoutRunnable)
		mainHandler.post(relayoutRunnable)
	}

	private val relayoutRunnable = Runnable { applyState() }
}
