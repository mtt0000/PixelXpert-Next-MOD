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
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.UUID
import sh.anirudhgupta109.pxn.R
import sh.anirudhgupta109.pxn.xposed.XPLauncher
import sh.anirudhgupta109.pxn.xposed.utils.reflection.ReflectedClass
import sh.anirudhgupta109.pxn.xposed.utils.toolkit.Logger

/**
 * Extra buttons on the screenshot shelf ("Auto-scroll", "Delete"), for
 * [ScreenshotManager], which runs in SystemUI's `screenshot` process.
 *
 * The hook point is `ScreenshotController.handleScreenshot`, a large method
 * neither R8 nor ART will inline: small constructors such as ActionsCallback's
 * or ScreenshotSavedResult's are compiled into their callers on Pixel builds
 * and their hooks never fire. Once it returned, the actions provider of the
 * new screenshot exists and has added the stock buttons, and the buttons get
 * in by one of two routes:
 *  1. the provider's ActionsCallback.provideActionButton, which every
 *     provider calls, so R8 keeps it;
 *  2. otherwise, appending ActionButtonViewModels straight to the `_actions`
 *     flow of the ScreenshotViewModel (whose addAction R8 removed).
 * The objects are found by field type, not field name, from the controller
 * down. Every step logs with the "PXN ScreenshotButtons:" prefix.
 */
class ScreenshotShelfButtons(
	private val context: Context,
	private val onAutoScroll: (displayId: Int) -> Unit,
) {
	var autoScrollEnabled = false
	var deleteEnabled = false

	private val mainHandler = Handler(Looper.getMainLooper())

	private var actionExecutor: Any? = null
	private var provider: Any? = null
	private var shotStartedAt = 0L
	private var displayId = 0

	/** From the ScreenshotSavedResult constructor, when that hook fires; the provider's own result comes first. */
	@Volatile
	private var savedUri: Uri? = null

	@Volatile
	private var savedAt = 0L

	private var nextDirectId = DIRECT_ID_BASE

	fun hook() {
		val controllerClass = ReflectedClass.ofIfPossible("$PKG.ScreenshotController")
		val savedResultClass = ReflectedClass.ofIfPossible(SAVED_RESULT)
		Logger.log(
			"PXN ScreenshotButtons: classes - ScreenshotController ${controllerClass.clazz != null}, " +
				"ScreenshotSavedResult ${savedResultClass.clazz != null}",
		)

		val hooks = controllerClass
			.after("handleScreenshot")
			.run { param ->
				shotStartedAt = SystemClock.uptimeMillis()
				Logger.log("PXN ScreenshotButtons: new screenshot (auto-scroll $autoScrollEnabled, delete $deleteEnabled)")
				if (!autoScrollEnabled && !deleteEnabled) return@run
				val controller = param.thisObject ?: return@run
				val screenshot = param.args.getOrNull(0)
				// After whatever else the shelf queues for this screenshot.
				mainHandler.post {
					try {
						addButtons(controller, screenshot)
					} catch (t: Throwable) {
						Logger.log("PXN ScreenshotButtons: could not add the buttons", t)
					}
				}
			}
		Logger.log("PXN ScreenshotButtons: handleScreenshot hooks: ${hooks.size}")

		savedResultClass
			.afterConstruction()
			.run { param ->
				savedUri = runCatching { getObjectField(param.thisObject, "uri") as Uri }.getOrNull()
				savedAt = SystemClock.uptimeMillis()
			}
	}

	private fun addButtons(controller: Any, screenshot: Any?) {
		val actionsController = fieldOfType(controller, "$PKG.ScreenshotActionsController")
			?: throw IllegalStateException("no ScreenshotActionsController in ${controller.javaClass.name}")
		actionExecutor = fieldOfType(actionsController, "$PKG.ActionExecutor") ?: fieldOfType(controller, "$PKG.ActionExecutor")

		val providers = fieldOfType(actionsController, Map::class.java.name) as? Map<*, *>
		val currentId = fieldOfType(actionsController, UUID::class.java.name)
		provider = providers?.get(currentId)
		val callback = fieldOfType(provider, ACTIONS_CALLBACK)

		// A provided image (an app handing SystemUI a bitmap) has nothing on screen to scroll.
		val type = runCatching { getObjectField(screenshot, "type") as Int }.getOrDefault(-1)
		displayId = runCatching { getObjectField(screenshot, "displayId") as Int }.getOrDefault(0)
		val withAutoScroll = autoScrollEnabled && type != TAKE_SCREENSHOT_PROVIDED_IMAGE
		Logger.log(
			"PXN ScreenshotButtons: provider ${provider?.javaClass?.name}, callback ${callback != null}, " +
				"executor ${actionExecutor != null}, display $displayId, type $type",
		)

		if (callback != null) {
			try {
				addThroughCallback(callback, withAutoScroll)
				return
			} catch (t: Throwable) {
				Logger.log("PXN ScreenshotButtons: route 1 (ActionsCallback) failed", t)
			}
		}
		val viewModel = fieldOfType(actionsController, "$PKG.ui.viewmodel.ScreenshotViewModel")
			?: throw IllegalStateException("no ScreenshotViewModel in ScreenshotActionsController")
		addThroughViewModel(viewModel, withAutoScroll)
	}

	// ------------------------------------------------------------ route 1

	private fun addThroughCallback(callback: Any, withAutoScroll: Boolean) {
		// provideActionButton(appearance, showDuringEntrance, onClick: () -> Unit)
		val provide = callback.javaClass.declaredMethods.firstOrNull { it.name == "provideActionButton" }
			?: callback.javaClass.declaredMethods.first {
				val types = it.parameterTypes
				types.size == 3 && types[1] == Boolean::class.javaPrimitiveType && types[2].name == FUNCTION0
			}
		provide.isAccessible = true
		val appearanceClass = provide.parameterTypes[0]
		val functionClass = provide.parameterTypes[2]

		if (withAutoScroll) {
			provide.invoke(callback, autoScrollAppearance(appearanceClass), true, function(functionClass) { autoScrollClicked() })
		}
		if (deleteEnabled) {
			provide.invoke(callback, deleteAppearance(appearanceClass), true, function(functionClass) { deleteClicked() })
		}
		Logger.log("PXN ScreenshotButtons: buttons added through ActionsCallback")
	}

	// ------------------------------------------------------------ route 2

	private fun addThroughViewModel(viewModel: Any, withAutoScroll: Boolean) {
		val loader = viewModel.javaClass.classLoader
		val flow = getObjectField(viewModel, "_actions")
		val current = callMethod(flow, "getValue") as List<*>

		val itemClass = Class.forName("$PKG.ui.viewmodel.ActionButtonViewModel", false, loader)
		// ActionButtonViewModel(appearance, id, visible, showDuringEntrance, onClicked)
		val constructor = itemClass.constructors.first {
			val types = it.parameterTypes
			types.size == 5 && types[1] == Int::class.javaPrimitiveType && types[4].name == FUNCTION0
		}
		val appearanceClass = constructor.parameterTypes[0]
		val functionClass = constructor.parameterTypes[4]

		val added = ArrayList<Any?>(current)
		if (withAutoScroll) {
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
		val shotProvider = provider
		Thread {
			val deadline = SystemClock.uptimeMillis() + SAVE_WAIT
			var uri = savedUriOf(shotProvider, startedAt)
			while (uri == null && SystemClock.uptimeMillis() < deadline) {
				SystemClock.sleep(50)
				uri = savedUriOf(shotProvider, startedAt)
			}
			val deleted = uri != null && try {
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

	/** The provider's own ScreenshotSavedResult, else the one the constructor hook saw for this screenshot. */
	private fun savedUriOf(shotProvider: Any?, startedAt: Long): Uri? {
		val result = fieldOfType(shotProvider, SAVED_RESULT)
		if (result != null) return runCatching { getObjectField(result, "uri") as Uri }.getOrNull()
		return if (savedAt >= startedAt) savedUri else null
	}

	/** First field of [owner] (or its superclasses) whose type is, or implements, [typeName]. */
	private fun fieldOfType(owner: Any?, typeName: String): Any? {
		owner ?: return null
		var clazz: Class<*>? = owner.javaClass
		while (clazz != null && clazz != Any::class.java) {
			for (field in clazz.declaredFields) {
				if (Modifier.isStatic(field.modifiers) || !isOfType(field.type, typeName)) continue
				field.isAccessible = true
				return field.get(owner) ?: continue
			}
			clazz = clazz.superclass
		}
		return null
	}

	private fun isOfType(type: Class<*>, typeName: String): Boolean {
		var current: Class<*>? = type
		while (current != null) {
			if (current.name == typeName || current.interfaces.any { it.name == typeName }) return true
			current = current.superclass
		}
		return false
	}

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

	private fun toast(text: Int) {
		val message = XPLauncher.moduleResources?.getString(text) ?: return
		mainHandler.post { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
	}

	private companion object {
		const val FUNCTION0 = "kotlin.jvm.functions.Function0"
		const val PKG = "com.android.systemui.screenshot"
		const val ACTIONS_CALLBACK = "$PKG.ScreenshotActionsController\$ActionsCallback"
		const val SAVED_RESULT = "$PKG.ScreenshotSavedResult"

		/** WindowManager.TAKE_SCREENSHOT_PROVIDED_IMAGE, hidden. */
		const val TAKE_SCREENSHOT_PROVIDED_IMAGE = 3

		/** Far above the ids ActionButtonViewModel.withNextId hands out. */
		const val DIRECT_ID_BASE = 1_000_000

		/** How long "Delete" waits for a screenshot that is still being saved, in ms. */
		const val SAVE_WAIT = 5000L
	}
}
