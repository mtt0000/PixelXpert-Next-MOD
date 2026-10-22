package sh.anirudhgupta109.pxn.xposed.utils

import android.graphics.Rect
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import java.util.WeakHashMap
import java.util.concurrent.CopyOnWriteArrayList
import sh.anirudhgupta109.pxn.xposed.XPLauncher
import sh.anirudhgupta109.pxn.xposed.utils.reflection.HookHelper
import sh.anirudhgupta109.pxn.xposed.utils.reflection.ReflectedClass
import sh.anirudhgupta109.pxn.xposed.utils.toolkit.Logger

/**
 * Puts small round buttons on every card (TaskView) of the Pixel Launcher's
 * Recents screen, on behalf of any number of [Provider]s (KillAppMod, HideAppMod).
 *
 * Why it is built like this (the previous attempt never showed a button):
 *  - the hook DSL only hooks methods DECLARED by the hooked class, and TaskView
 *    does not declare onLayout/dispatchTouchEvent in recent launchers, so hooks
 *    on TaskView silently matched nothing. Here nothing is hooked on TaskView.
 *  - buttons are injected from a layout listener attached to every RecentsView
 *    instance (RecentsView's constructor is hooked, exactly like ClearAllButtonMod).
 *  - taps are routed by a touch hook with a hit-test, because Recents/DragLayer
 *    touch controllers can swallow taps meant for child views. A plain
 *    OnClickListener is kept on every button as a fallback.
 */
object TaskButtonsHost {

	const val TAG_PREFIX: String = "pxn_task_btn_"

	interface Provider {
		/** View tag of the button, must start with [TAG_PREFIX]. */
		val tag: String

		/** 0 = right-most position on the card, 1 = next one to the left, ... */
		val slot: Int

		/** Drawable resource id in the MODULE resources. */
		val iconRes: Int

		fun isEnabled(): Boolean

		/** The user tapped this provider's button on [taskView]. */
		fun onClick(taskView: ViewGroup)

		/** Called for every card after each refresh. */
		fun onRefresh(taskView: ViewGroup) {}
	}

	/** A button under the finger and what tapping it does. */
	private class Hit(val button: View, val action: () -> Unit)

	private class Refresher(val recentsView: ViewGroup) : Runnable {
		var queued = false

		override fun run() {
			queued = false
			TaskButtonsHost.refresh(recentsView)
		}
	}

	private val providers = CopyOnWriteArrayList<Provider>()
	private val refreshers = WeakHashMap<ViewGroup, Refresher>()
	private var installed = false
	private var pressed: Hit? = null

	/** Buttons outside the cards (manage hidden apps) that get the same tap routing. */
	private val extraButtons = java.util.Collections.newSetFromMap(WeakHashMap<View, Boolean>())

	/**
	 * Routes taps on [button] through the hit-test hook too, so the launcher's
	 * touch controllers cannot swallow them. Its own click listener is what runs.
	 */
	@JvmStatic
	fun registerExtraButton(button: View) {
		synchronized(extraButtons) { extraButtons.add(button) }
	}

	@JvmStatic
	fun register(provider: Provider) {
		if (!providers.contains(provider)) providers.add(provider)
		install()
	}

	@JvmStatic
	fun requestRefresh() {
		val all = synchronized(refreshers) { refreshers.keys.toList() }
		for (rv in all) schedule(rv)
	}

	// ----------------------------------------------------------------- install

	@Synchronized
	private fun install() {
		if (installed) return
		installed = true

		val recentsViewClass = ReflectedClass.of("com.android.quickstep.views.RecentsView")
		val recentsClazz = recentsViewClass.clazz
		if (recentsClazz == null) {
			Logger.log(RecentsTaskTools.LOG + "RecentsView class not found, task buttons disabled")
			return
		}

		recentsViewClass.afterConstruction().run { param ->
			val view = param.thisObject as? ViewGroup
			if (view != null) attach(view)
		}

		// setVisibility is declared by RecentsView on the launchers where ClearAllButtonMod works.
		recentsViewClass.after("setVisibility").run { param ->
			val view = param.thisObject as? ViewGroup
			if (view != null) {
				attach(view)
				schedule(view)
			}
		}

		var refreshHooks = 0
		for (name in arrayOf("applyLoadPlan", "onLayout", "onPageScrollsInitialized", "updateTaskSize")) {
			refreshHooks += hookInHierarchy(recentsClazz, name, false) { param ->
				val view = param.thisObject as? ViewGroup
				if (view != null) {
					attach(view)
					schedule(view)
				}
			}
		}

		var touchHooks = 0
		val dragLayer = ReflectedClass.ofIfPossible("com.android.launcher3.views.BaseDragLayer").clazz
		if (dragLayer != null) {
			touchHooks += hookInHierarchy(dragLayer, "dispatchTouchEvent", true) { param ->
				val event = param.args[0] as? MotionEvent
				if (event != null && handleTouch(event)) param.setResult(true)
			}
		}
		touchHooks += hookInHierarchy(recentsClazz, "onInterceptTouchEvent", true) { param ->
			val event = param.args[0] as? MotionEvent
			if (event != null && handleTouch(event)) param.setResult(true)
		}
		touchHooks += hookInHierarchy(recentsClazz, "onTouchEvent", true) { param ->
			val event = param.args[0] as? MotionEvent
			if (event != null && handleTouch(event)) param.setResult(true)
		}

		Logger.log(RecentsTaskTools.LOG + "installed: refreshHooks=" + refreshHooks + " touchHooks=" + touchHooks)
	}

	/**
	 * Hooks [name] on the NEAREST class (starting at [base], walking up, stopping at the
	 * framework) that really declares it, and only reacts to instances of [base].
	 * Returns how many methods were hooked.
	 */
	@JvmStatic
	fun hookInHierarchy(
		base: Class<*>,
		name: String,
		runBefore: Boolean,
		consumer: ReflectedClass.ReflectionConsumer,
	): Int {
		val iface = XPLauncher.instance ?: return 0

		val guarded = ReflectedClass.ReflectionConsumer { param ->
			if (base.isInstance(param.thisObject)) {
				try {
					consumer.run(param)
				} catch (t: Throwable) {
					Logger.log(RecentsTaskTools.LOG + "hook callback for $name failed", t)
				}
			}
		}

		var c: Class<*>? = base
		while (c != null && c != Any::class.java && !c.name.startsWith("android.") && !c.name.startsWith("java.")) {
			val methods = c.declaredMethods.filter { it.name == name }
			if (methods.isNotEmpty()) {
				var count = 0
				for (m in methods) {
					try {
						HookHelper.hookMethod(m, guarded, runBefore, iface)
						count++
					} catch (t: Throwable) {
						Logger.log(RecentsTaskTools.LOG + "cannot hook " + c.name + "." + name, t)
					}
				}
				Logger.log(RecentsTaskTools.LOG + "hooked " + count + " x " + c.name + "." + name)
				return count
			}
			c = c.superclass
		}
		Logger.log(RecentsTaskTools.LOG + "no declaring class for " + base.name + "." + name)
		return 0
	}

	// ----------------------------------------------------------------- refresh

	private fun attach(recentsView: ViewGroup) {
		synchronized(refreshers) {
			if (refreshers.containsKey(recentsView)) return
			refreshers[recentsView] = Refresher(recentsView)
		}
		recentsView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> schedule(recentsView) }
		schedule(recentsView)
	}

	private fun schedule(recentsView: ViewGroup) {
		val refresher = synchronized(refreshers) { refreshers[recentsView] } ?: return
		if (refresher.queued) return
		refresher.queued = true
		recentsView.post(refresher)
	}

	@JvmStatic
	fun refresh(recentsView: ViewGroup) {
		try {
			for (taskView in RecentsTaskTools.collectTaskViews(recentsView)) {
				for (provider in providers) ensureButton(taskView, provider)
				for (provider in providers) provider.onRefresh(taskView)
			}
		} catch (t: Throwable) {
			Logger.log(RecentsTaskTools.LOG + "refresh failed", t)
		}
	}

	private fun isOurTag(tag: Any?): Boolean = tag is String && tag.startsWith(TAG_PREFIX)

	private fun needsFront(taskView: ViewGroup, button: View): Boolean {
		val index = taskView.indexOfChild(button)
		for (i in index + 1 until taskView.childCount) {
			if (!isOurTag(taskView.getChildAt(i).tag)) return true
		}
		return false
	}

	private fun ensureButton(taskView: ViewGroup, provider: Provider) {
		var button = taskView.findViewWithTag<View>(provider.tag)

		if (!provider.isEnabled()) {
			if (button != null && button.visibility != View.GONE) button.visibility = View.GONE
			return
		}

		val context = taskView.context
		if (button == null) {
			val created = RecentsTaskTools.createRoundButton(context, provider.iconRes, provider.tag, 8)
			val size = RecentsTaskTools.dp(context, 40f)
			val margin = RecentsTaskTools.dp(context, 8f)

			val params = FrameLayout.LayoutParams(size, size)
			params.gravity = Gravity.TOP or Gravity.END
			params.topMargin = margin
			params.marginEnd = margin + provider.slot * (size + margin)
			created.layoutParams = params

			created.setOnClickListener { provider.onClick(taskView) }
			created.elevation = RecentsTaskTools.dp(context, 8f).toFloat()
			created.translationZ = 100f

			taskView.addView(created)
			button = created
		}

		if (button.visibility != View.VISIBLE) button.visibility = View.VISIBLE
		if (needsFront(taskView, button)) button.bringToFront()
	}

	// ------------------------------------------------------------------- touch

	private fun contains(button: View, x: Float, y: Float): Boolean {
		val rect = Rect()
		if (!button.getGlobalVisibleRect(rect)) return false
		val root = IntArray(2)
		button.rootView.getLocationOnScreen(root)
		rect.offset(root[0], root[1])
		val slop = RecentsTaskTools.dp(button.context, 6f)
		rect.inset(-slop, -slop)
		return rect.contains(x.toInt(), y.toInt())
	}

	/**
	 * True when [view] or an ancestor is (almost) transparent: Recents can sit
	 * VISIBLE at alpha 0 behind the home screen, and a tap there must not land
	 * on an invisible button.
	 */
	private fun isFadedOut(view: View): Boolean {
		var alpha = 1f
		var current: View? = view
		while (current != null) {
			alpha *= current.alpha
			current = current.parent as? View
		}
		return alpha < 0.1f
	}

	private fun findButtonAt(x: Float, y: Float): Hit? {
		val all = synchronized(refreshers) { refreshers.keys.toList() }
		for (recentsView in all) {
			if (!recentsView.isShown) continue
			for (taskView in RecentsTaskTools.collectTaskViews(recentsView)) {
				for (provider in providers) {
					if (!provider.isEnabled()) continue
					val button = taskView.findViewWithTag<View>(provider.tag) ?: continue
					if (button.visibility != View.VISIBLE || !button.isShown || isFadedOut(button)) continue
					if (contains(button, x, y)) return Hit(button) { provider.onClick(taskView) }
				}
			}
		}
		val extras = synchronized(extraButtons) { extraButtons.toList() }
		for (button in extras) {
			if (!button.isAttachedToWindow || button.visibility != View.VISIBLE || !button.isShown || isFadedOut(button)) continue
			if (contains(button, x, y)) return Hit(button) { button.performClick() }
		}
		return null
	}

	/** @return true when the event belongs to one of our buttons and must be consumed. */
	private fun handleTouch(event: MotionEvent): Boolean {
		if (providers.isEmpty() && synchronized(extraButtons) { extraButtons.isEmpty() }) return false

		val action = event.actionMasked
		if (action == MotionEvent.ACTION_DOWN) {
			pressed?.button?.isPressed = false
			pressed = null

			val hit = findButtonAt(event.rawX, event.rawY) ?: return false
			hit.button.isPressed = true
			pressed = hit
			return true
		}

		val current = pressed ?: return false
		when (action) {
			MotionEvent.ACTION_MOVE ->
				current.button.isPressed = contains(current.button, event.rawX, event.rawY)

			MotionEvent.ACTION_UP -> {
				val inside = contains(current.button, event.rawX, event.rawY)
				current.button.isPressed = false
				pressed = null
				if (inside) current.action()
			}

			MotionEvent.ACTION_CANCEL -> {
				current.button.isPressed = false
				pressed = null
			}

			else -> Unit
		}
		return true
	}
}
