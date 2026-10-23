package sh.anirudhgupta109.pxn.xposed.modpacks.systemui

import android.content.Context
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.core.content.res.ResourcesCompat
import de.robv.android.xposed.XposedHelpers.callMethod
import de.robv.android.xposed.XposedHelpers.getObjectField
import java.lang.reflect.Proxy
import sh.anirudhgupta109.pxn.R
import sh.anirudhgupta109.pxn.xposed.XPLauncher
import sh.anirudhgupta109.pxn.xposed.utils.reflection.ReflectedClass
import sh.anirudhgupta109.pxn.xposed.utils.toolkit.Logger

/**
 * Extra buttons on the screenshot shelf ("Auto-scroll", "Delete"), for
 * [ScreenshotManager], which runs in SystemUI's `screenshot` process.
 *
 * Google's SystemUI is shrunk by R8, which may inline single-caller methods
 * such as ScreenshotViewModel.addAction or setCurrentScreenshot away, so the
 * buttons get in by two independent routes:
 *  1. the per-screenshot `ScreenshotActionsController$ActionsCallback`: its
 *     constructor is hooked and provideActionButton - called by every actions
 *     provider, so it stays - is invoked right after;
 *  2. when the screenshot is saved (`ScreenshotSavedResult` constructor), if
 *     route 1 did not add them, they are appended to the `_actions` flow of
 *     the `ScreenshotViewModel` singleton directly.
 * The instances it needs (view model, ActionExecutor) are caught from their
 * constructors. Every step logs with the "PXN ScreenshotButtons:" prefix.
 */
class ScreenshotShelfButtons(
	private val context: Context,
	private val onAutoScroll: (displayId: Int) -> Unit,
) {
	var autoScrollEnabled = false
	var deleteEnabled = false

	private val mainHandler = Handler(Looper.getMainLooper())

	private var viewModel: Any? = null
	private var actionExecutor: Any? = null

	/** Set by route 1 for the current screenshot, consumed by route 2. */
	private var addedThroughCallback = false
	private var shotStartedAt = 0L
	private var displayId = 0

	@Volatile
	private var savedUri: Uri? = null

	@Volatile
	private var savedAt = 0L

	private var nextDirectId = DIRECT_ID_BASE

	fun hook() {
		val viewModelClass = ReflectedClass.ofIfPossible("com.android.systemui.screenshot.ui.viewmodel.ScreenshotViewModel")
		val executorClass = ReflectedClass.ofIfPossible("com.android.systemui.screenshot.ActionExecutor")
		val callbackClass = ReflectedClass.ofIfPossible("com.android.systemui.screenshot.ScreenshotActionsController\$ActionsCallback")
		val savedResultClass = ReflectedClass.ofIfPossible("com.android.systemui.screenshot.ScreenshotSavedResult")
		Logger.log(
			"PXN ScreenshotButtons: classes - ScreenshotViewModel ${viewModelClass.clazz != null}, " +
				"ActionExecutor ${executorClass.clazz != null}, ActionsCallback ${callbackClass.clazz != null}, " +
				"ScreenshotSavedResult ${savedResultClass.clazz != null}",
		)

		viewModelClass
			.afterConstruction()
			.run { param -> viewModel = param.thisObject }

		executorClass
			.afterConstruction()
			.run { param -> actionExecutor = param.thisObject }

		callbackClass
			.afterConstruction()
			.run { param ->
				val callback = param.thisObject ?: return@run
				addedThroughCallback = false
				shotStartedAt = SystemClock.uptimeMillis()
				Logger.log("PXN ScreenshotButtons: new screenshot (auto-scroll $autoScrollEnabled, delete $deleteEnabled)")
				if (!autoScrollEnabled && !deleteEnabled) return@run
				// After the stock buttons, which the provider adds right after this constructor.
				mainHandler.post {
					try {
						addThroughCallback(callback)
						addedThroughCallback = true
					} catch (t: Throwable) {
						Logger.log("PXN ScreenshotButtons: route 1 (ActionsCallback) failed", t)
					}
				}
			}

		savedResultClass
			.afterConstruction()
			.run { param ->
				savedUri = runCatching { getObjectField(param.thisObject, "uri") as Uri }.getOrNull()
				savedAt = SystemClock.uptimeMillis()
				Logger.log("PXN ScreenshotButtons: screenshot saved")
				if (!autoScrollEnabled && !deleteEnabled) return@run
				mainHandler.post {
					if (addedThroughCallback) {
						addedThroughCallback = false
						return@post
					}
					try {
						addThroughViewModel()
					} catch (t: Throwable) {
						Logger.log("PXN ScreenshotButtons: route 2 (ScreenshotViewModel) failed", t)
					}
				}
			}
	}

	// ------------------------------------------------------------ route 1

	private fun addThroughCallback(callback: Any) {
		val controller = runCatching { getObjectField(callback, "this\$0") }.getOrNull()
		val provider = runCatching {
			val id = getObjectField(callback, "screenshotId")
			(getObjectField(controller, "actionProviders") as Map<*, *>)[id]
		}.getOrNull()
		val screenshot = runCatching { getObjectField(provider, "request") }.getOrNull()
		runCatching { getObjectField(controller, "actionExecutor") }.getOrNull()?.let { actionExecutor = it }

		// A provided image (an app handing SystemUI a bitmap) has nothing on screen to scroll.
		val type = runCatching { getObjectField(screenshot, "type") as Int }.getOrDefault(-1)
		displayId = runCatching { getObjectField(screenshot, "displayId") as Int }.getOrDefault(0)

		// provideActionButton(appearance, showDuringEntrance, onClick: () -> Unit)
		val provide = callback.javaClass.declaredMethods.firstOrNull { it.name == "provideActionButton" }
			?: callback.javaClass.declaredMethods.first {
				val types = it.parameterTypes
				types.size == 3 && types[1] == Boolean::class.javaPrimitiveType && types[2].name == FUNCTION0
			}
		provide.isAccessible = true
		val appearanceClass = provide.parameterTypes[0]
		val functionClass = provide.parameterTypes[2]

		if (autoScrollEnabled && type != TAKE_SCREENSHOT_PROVIDED_IMAGE) {
			provide.invoke(callback, autoScrollAppearance(appearanceClass), true, function(functionClass) { autoScrollClicked() })
		}
		if (deleteEnabled) {
			provide.invoke(callback, deleteAppearance(appearanceClass), true, function(functionClass) { deleteClicked() })
		}
		Logger.log("PXN ScreenshotButtons: buttons added through ActionsCallback (display $displayId)")
	}

	// ------------------------------------------------------------ route 2

	private fun addThroughViewModel() {
		val vm = viewModel ?: throw IllegalStateException("no ScreenshotViewModel instance")
		val loader = vm.javaClass.classLoader
		val flow = getObjectField(vm, "_actions")
		val current = callMethod(flow, "getValue") as List<*>
		val ours = setOf(label(R.string.scroll_capture_label), label(R.string.screenshot_delete_label))
		if (current.any { runCatching { getObjectField(getObjectField(it, "appearance"), "description").toString() in ours }.getOrDefault(false) }) {
			return
		}
		// The screenshot came in without route 1: this is a new one.
		shotStartedAt = savedAt
		displayId = 0

		val itemClass = Class.forName("com.android.systemui.screenshot.ui.viewmodel.ActionButtonViewModel", false, loader)
		// ActionButtonViewModel(appearance, id, visible, showDuringEntrance, onClicked)
		val constructor = itemClass.constructors.first {
			val types = it.parameterTypes
			types.size == 5 && types[1] == Int::class.javaPrimitiveType && types[4].name == FUNCTION0
		}
		val appearanceClass = constructor.parameterTypes[0]
		val functionClass = constructor.parameterTypes[4]

		val added = ArrayList<Any?>(current)
		if (autoScrollEnabled) {
			added += constructor.newInstance(autoScrollAppearance(appearanceClass), nextDirectId++, true, true, function(functionClass) { autoScrollClicked() })
		}
		if (deleteEnabled) {
			added += constructor.newInstance(deleteAppearance(appearanceClass), nextDirectId++, true, true, function(functionClass) { deleteClicked() })
		}
		callMethod(flow, "setValue", added)
		Logger.log("PXN ScreenshotButtons: buttons added through ScreenshotViewModel")
	}

	// ------------------------------------------------------------ actions

	private fun autoScrollClicked() {
		dismissShelf()
		onAutoScroll(displayId)
	}

	/** Deletes the screenshot on screen; waits for it to be saved if needed. */
	private fun deleteClicked() {
		dismissShelf()
		val startedAt = shotStartedAt
		Thread {
			val deadline = SystemClock.uptimeMillis() + SAVE_WAIT
			while (savedAt < startedAt && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
			val uri = savedUri
			val deleted = uri != null && savedAt >= startedAt && try {
				// SystemUI inserted the row itself, so it may delete it without a consent dialog.
				context.contentResolver.delete(uri, null, null) > 0
			} catch (t: Throwable) {
				Logger.log(t)
				false
			}
			toast(if (deleted) R.string.screenshot_deleted else R.string.screenshot_delete_failed)
		}.start()
	}

	/** Animated dismissal through the shelf's view proxy, else ActionExecutor.finishDismiss. */
	private fun dismissShelf() {
		val executor = actionExecutor ?: return Logger.log("PXN ScreenshotButtons: no ActionExecutor to dismiss the shelf")
		try {
			callMethod(getObjectField(executor, "viewProxy"), "requestDismissal", *arrayOf<Any?>(null))
		} catch (t: Throwable) {
			try {
				callMethod(getObjectField(executor, "finishDismiss"), "invoke")
			} catch (t2: Throwable) {
				Logger.log("PXN ScreenshotButtons: could not dismiss the shelf", t2)
			}
		}
	}

	// ------------------------------------------------------------ helpers

	/** A SystemUI-side kotlin Function0 (other classloader than ours) running [block]. */
	private fun function(functionClass: Class<*>, block: () -> Unit): Any {
		val loader = functionClass.classLoader
		return Proxy.newProxyInstance(loader, arrayOf(functionClass)) { proxy, method, args ->
			when (method.name) {
				"invoke" -> {
					try {
						block()
					} catch (t: Throwable) {
						Logger.log(t)
					}
					Class.forName("kotlin.Unit", false, loader).getField("INSTANCE").get(null)
				}
				"equals" -> proxy === args?.getOrNull(0)
				"hashCode" -> System.identityHashCode(proxy)
				else -> "PXN screenshot button"
			}
		}
	}

	private fun autoScrollAppearance(appearanceClass: Class<*>) =
		appearance(appearanceClass, R.drawable.ic_scroll_capture, R.string.scroll_capture_label)

	private fun deleteAppearance(appearanceClass: Class<*>) =
		appearance(appearanceClass, R.drawable.ic_delete_screenshot, R.string.screenshot_delete_label)

	/**
	 * `ActionButtonAppearance(icon, label, description, tint = true, ...)`: the
	 * shortest constructor wins (the @JvmOverloads one, if R8 kept it), extra
	 * parameters get the Kotlin defaults.
	 */
	private fun appearance(appearanceClass: Class<*>, icon: Int, text: Int): Any {
		val res = XPLauncher.moduleResources!!
		val drawable = ResourcesCompat.getDrawable(res, icon, context.theme)
		val label = res.getString(text)
		val constructor = appearanceClass.constructors
			.filter { ctor ->
				val types = ctor.parameterTypes
				types.size >= 3 &&
					types[0] == Drawable::class.java &&
					types[1] == CharSequence::class.java &&
					types[2] == CharSequence::class.java
			}
			.minBy { it.parameterTypes.size }
		val args = constructor.parameterTypes.mapIndexed { index, type ->
			when {
				index == 0 -> drawable
				index == 1 || index == 2 -> label
				type == Boolean::class.javaPrimitiveType -> true
				type == Int::class.javaPrimitiveType -> 0
				else -> null
			}
		}
		return constructor.newInstance(*args.toTypedArray())
	}

	private fun label(text: Int): String = XPLauncher.moduleResources?.getString(text) ?: ""

	private fun toast(text: Int) {
		val message = XPLauncher.moduleResources?.getString(text) ?: return
		mainHandler.post { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
	}

	private companion object {
		const val FUNCTION0 = "kotlin.jvm.functions.Function0"

		/** WindowManager.TAKE_SCREENSHOT_PROVIDED_IMAGE, hidden. */
		const val TAKE_SCREENSHOT_PROVIDED_IMAGE = 3

		/** Far above the ids ActionButtonViewModel.withNextId hands out. */
		const val DIRECT_ID_BASE = 1_000_000

		/** How long "Delete" waits for a screenshot that is still being saved, in ms. */
		const val SAVE_WAIT = 5000L
	}
}
