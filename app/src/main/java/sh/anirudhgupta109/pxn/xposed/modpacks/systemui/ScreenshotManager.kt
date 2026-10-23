package sh.anirudhgupta109.pxn.xposed.modpacks.systemui

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Drawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.view.Display
import androidx.core.content.res.ResourcesCompat
import de.robv.android.xposed.XposedHelpers.callMethod
import de.robv.android.xposed.XposedHelpers.getIntField
import de.robv.android.xposed.XposedHelpers.getObjectField
import de.robv.android.xposed.XposedHelpers.setObjectField
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Proxy
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import sh.anirudhgupta109.pxn.R
import sh.anirudhgupta109.pxn.annotations.ChildProcessModPack
import sh.anirudhgupta109.pxn.xposed.XPLauncher
import sh.anirudhgupta109.pxn.xposed.XPrefs.Xprefs
import sh.anirudhgupta109.pxn.xposed.XposedModPack
import sh.anirudhgupta109.pxn.xposed.annotations.SystemUIModPack
import sh.anirudhgupta109.pxn.xposed.utils.reflection.ReflectedClass

/**
 * Adjusts the screenshot pipeline inside SystemUI, running in the dedicated
 * `screenshot` child process as well as the main one.
 *
 * Two preferences drive it: "disable screenshot sound" silences the shutter by
 * neutering `MediaPlayer.start()` outright and by swapping the coroutine
 * dispatcher the sound controller(s) run on for an executor that never runs
 * anything - covering both the QPR1 sound-controller constructor and the QPR2
 * executor path - while "screenshot chord insecure" lets the chord capture
 * secure content by forcing the `CaptureArgs` secure flags and short-circuiting
 * the managed-profile / user-info policy checks that would otherwise refuse.
 *
 * "delete shared screenshots" (Samsung One UI has the same option) removes a
 * screenshot from the gallery once it was shared from the screenshot shelf.
 * The share chooser built by `ActionIntentCreator` gets an IntentSender that
 * fires only when a target is actually picked - cancelling the sheet keeps the
 * file - and the MediaStore entry is deleted after a grace period, because the
 * receiving app reads the URI after the chooser has closed.
 *
 * "auto-scroll capture" adds a button to the screenshot shelf, next to the
 * platform "Capture more" chip (which only shows up for apps that implement
 * scroll capture): it dismisses the shelf and runs [AutoScrollCapture], which
 * scrolls the app itself and stitches the frames, like Samsung's scroll
 * capture - one page per tap on its floating "Scroll" button, or by itself
 * until "Stop" / a tap on the screen when manual mode is off.
 */
@SystemUIModPack
@ChildProcessModPack(processNameContains = "screenshot")
class ScreenshotManager(context: Context) : XposedModPack(context) {

	companion object {
		private var disableScreenshotSound = false

		private const val ACTION_SCREENSHOT_SHARED = "sh.anirudhgupta109.pxn.SCREENSHOT_SHARED"
		private const val EXTRA_SHARED_URI = "pxn_shared_uri"

		/** WindowManager.TAKE_SCREENSHOT_PROVIDED_IMAGE, hidden. */
		private const val TAKE_SCREENSHOT_PROVIDED_IMAGE = 3
		private const val SHELF_DISMISS_DELAY = 400L
	}

	private var ScreenshotChordInsecure = false
	private var ScreenshotDeleteShared = false
	private var ScreenshotDeleteSharedDelay = 60
	private var ScreenshotAutoScroll = false
	private var ScreenshotAutoScrollMaxSteps = 10
	private var ScreenshotAutoScrollManual = true

	/** SystemUI's ImageCaptureImpl, the display capture the screenshot itself uses. */
	private var imageCapture: Any? = null

	private var sharedReceiverRegistered = false
	private var sharedRequestCode = 0
	private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

	override fun onPreferenceUpdated(vararg key: String?) {
		disableScreenshotSound = Xprefs.getBoolean("disableScreenshotSound", false)
		ScreenshotChordInsecure = Xprefs.getBoolean("ScreenshotChordInsecure", false)
		ScreenshotDeleteShared = Xprefs.getBoolean("ScreenshotDeleteShared", false)
		ScreenshotDeleteSharedDelay = Xprefs.getSliderInt("ScreenshotDeleteSharedDelay", 60)
		ScreenshotAutoScroll = Xprefs.getBoolean("ScreenshotAutoScroll", false)
		ScreenshotAutoScrollMaxSteps = Xprefs.getSliderInt("ScreenshotAutoScrollMaxSteps", 10)
		ScreenshotAutoScrollManual = Xprefs.getBoolean("ScreenshotAutoScrollManual", true)
	}

	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		val NewCaptureArgsClass = ReflectedClass.ofIfPossible("android.window.ScreenCaptureInternal.CaptureArgs") //A16QPR2
		val CaptureArgsClass = ReflectedClass.ofIfPossible("android.window.ScreenCapture.CaptureArgs") //A16QPR1
		val TakeScreenshotExecutorImplClass = ReflectedClass.of("com.android.systemui.screenshot.TakeScreenshotExecutorImpl")
		val ScreenshotSoundControllerImplClass = ReflectedClass.ofIfPossible("com.android.systemui.screenshot.ScreenshotSoundControllerImpl")

		ReflectedClass.of(UserManager::class.java)
			.before("getUserInfo")
			.run { param -> param.args[0] = 0 }

		val ScreenshotPolicyImplClass = ReflectedClass.ofIfPossible("com.android.systemui.screenshot.ScreenshotPolicyImpl")

		if (ScreenshotPolicyImplClass.clazz != null) {
			ScreenshotPolicyImplClass
				.before(Pattern.compile(".*isManagedProfile.*"))
				.run { param ->
					if (ScreenshotChordInsecure)
						param.setResult(false)
				}
		}

		NewCaptureArgsClass
			.afterConstruction()
			.run { param ->
				if (ScreenshotChordInsecure) {
					setObjectField(param.thisObject, "mSecureContentPolicy", 1) //No source available. but apparently 1 works.
				}
			}

		CaptureArgsClass
			.afterConstruction()
			.run { param ->
				if (ScreenshotChordInsecure) {
					setObjectField(param.thisObject, "mCaptureSecureLayers", true)
				}
			}

		// createShare / createShareWithSubject / createShareWithText all end in the
		// private createShare (and its static $default bridge), so one share runs
		// several of these hooks on the same chooser intent - see the extra check.
		ReflectedClass.ofIfPossible("com.android.systemui.screenshot.ActionIntentCreator")
			.after(Pattern.compile("createShare.*"))
			.run { param ->
				if (!ScreenshotDeleteShared) return@run
				val chooser = param.getResult<Any?>() as? Intent ?: return@run
				if (chooser.hasExtra(Intent.EXTRA_CHOSEN_COMPONENT_INTENT_SENDER)) return@run
				val uri = param.args.firstOrNull { it is Uri } as? Uri ?: return@run
				try {
					chooser.putExtra(Intent.EXTRA_CHOSEN_COMPONENT_INTENT_SENDER, sharedScreenshotSender(uri).intentSender)
				} catch (t: Throwable) {
					log(t)
				}
			}

		val imageCaptureClass = ReflectedClass.ofIfPossible("com.android.systemui.screenshot.ImageCaptureImpl")
		imageCaptureClass
			.afterConstruction()
			.run { param -> imageCapture = param.thisObject }

		// One ActionsCallback is built per screenshot, inside
		// ScreenshotActionsController.setCurrentScreenshot and right before the
		// actions provider adds the stock buttons through it. Hooking its
		// constructor instead of setCurrentScreenshot / ScreenshotViewModel.addAction
		// survives R8 on Pixel builds, where those single-caller methods can be
		// inlined away; provideActionButton is called by every provider, so it stays.
		// The post puts the button after the stock ones.
		val actionsCallbackClass = ReflectedClass.ofIfPossible("com.android.systemui.screenshot.ScreenshotActionsController\$ActionsCallback")
		if (actionsCallbackClass.clazz == null || imageCaptureClass.clazz == null) {
			log("PXN ScrollCapture: ActionsCallback found: ${actionsCallbackClass.clazz != null}, ImageCaptureImpl found: ${imageCaptureClass.clazz != null}")
		}
		actionsCallbackClass
			.afterConstruction()
			.run { param ->
				if (!ScreenshotAutoScroll) return@run
				val callback = param.thisObject ?: return@run
				mainHandler.post {
					try {
						addAutoScrollButton(callback)
					} catch (t: Throwable) {
						log("PXN ScrollCapture: could not add the button", t)
					}
				}
			}

		//17 - much easier approach: killing mediaplayer totally
		ReflectedClass.of(MediaPlayer::class.java)
			.before("start")
			.run { param ->
				if (disableScreenshotSound)
					param.setResult(null)
			}

		//16 qpr2
		TakeScreenshotExecutorImplClass
			.after("getScreenshotController")
			.run { param ->
				if (disableScreenshotSound) {
					setObjectField(
						getObjectField(param.getResult<Any?>(), "screenshotSoundController"),
						"bgDispatcher",
						ReflectedClass.of("kotlinx.coroutines.ExecutorCoroutineDispatcherImpl").clazz!!.constructors[0].newInstance(NoExecutor()),
					)
				}
			}

		//16 qpr1
		ScreenshotSoundControllerImplClass
			.beforeConstruction()
			.run { param ->
				if (disableScreenshotSound) {
					for (i in param.args.indices) {
						// Locale.getDefault() keeps this on the default locale's rules
						if (param.args[i]!!.javaClass.name.lowercase(Locale.getDefault()).contains("dispatcher")) {
							param.args[i] = ReflectedClass.of("kotlinx.coroutines.ExecutorCoroutineDispatcherImpl").clazz!!.constructors[0].newInstance(NoExecutor())
						}
					}
				}
			}
	}

	private fun addAutoScrollButton(callback: Any) {
		val controller = runCatching { getObjectField(callback, "this\$0") }.getOrNull()
		// The provider of this screenshot holds its request (ScreenshotData).
		val provider = runCatching {
			val id = getObjectField(callback, "screenshotId")
			(getObjectField(controller, "actionProviders") as Map<*, *>)[id]
		}.getOrNull()
		val screenshot = runCatching { getObjectField(provider, "request") }.getOrNull()

		// A provided image (an app handing SystemUI a bitmap) has nothing on screen to scroll.
		val type = runCatching { getIntField(screenshot, "type") }.getOrDefault(-1)
		if (type == TAKE_SCREENSHOT_PROVIDED_IMAGE) return
		val displayId = runCatching { getIntField(screenshot, "displayId") }.getOrDefault(Display.DEFAULT_DISPLAY)

		// provideActionButton(appearance, showDuringEntrance, onClick: () -> Unit)
		val provideActionButton = callback.javaClass.declaredMethods.firstOrNull { it.name == "provideActionButton" }
			?: callback.javaClass.declaredMethods.first {
				val types = it.parameterTypes
				types.size == 3 && types[1] == Boolean::class.javaPrimitiveType && types[2].name == "kotlin.jvm.functions.Function0"
			}
		provideActionButton.isAccessible = true
		val loader = callback.javaClass.classLoader

		val onClick = Proxy.newProxyInstance(loader, arrayOf(provideActionButton.parameterTypes[2])) { proxy, method, args ->
			when (method.name) {
				"invoke" -> {
					onAutoScrollClicked(controller, provider, displayId)
					Class.forName("kotlin.Unit", false, loader).getField("INSTANCE").get(null)
				}
				"equals" -> proxy === args?.getOrNull(0)
				"hashCode" -> System.identityHashCode(proxy)
				else -> "PXN auto-scroll capture"
			}
		}
		val id = provideActionButton.invoke(callback, autoScrollAppearance(provideActionButton.parameterTypes[0]), true, onClick)
		log("PXN ScrollCapture: button added (id $id, display $displayId)")
	}

	/**
	 * `ActionButtonAppearance(icon, label, description, tint = true, ...)`: the
	 * shortest constructor wins (the @JvmOverloads one), extra parameters get
	 * the Kotlin defaults.
	 */
	private fun autoScrollAppearance(appearanceClass: Class<*>): Any {
		val res = XPLauncher.moduleResources!!
		val icon = ResourcesCompat.getDrawable(res, R.drawable.ic_scroll_capture, mContext.theme)
		val label = res.getString(R.string.scroll_capture_label)
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
				index == 0 -> icon
				index == 1 || index == 2 -> label
				type == Boolean::class.javaPrimitiveType -> true
				type == Int::class.javaPrimitiveType -> 0
				else -> null
			}
		}
		return constructor.newInstance(*args.toTypedArray())
	}

	private fun onAutoScrollClicked(controller: Any?, provider: Any?, displayId: Int) {
		val capture = imageCapture
		if (capture == null) log("PXN ScrollCapture: ImageCaptureImpl instance not captured, capturing directly")
		if (AutoScrollCapture.running) return
		AutoScrollCapture.running = true

		// The shelf has to be gone before the first frame is captured; the injected
		// swipes would dismiss it anyway, as a touch outside.
		try {
			val executor = runCatching { getObjectField(controller, "actionExecutor") }.getOrNull()
				?: getObjectField(provider, "actionExecutor")
			callMethod(getObjectField(executor, "viewProxy"), "requestDismissal", *arrayOf<Any?>(null))
		} catch (t: Throwable) {
			log("PXN ScrollCapture: could not dismiss the shelf", t)
		}
		mainHandler.postDelayed({
			try {
				AutoScrollCapture(
					mContext,
					capture,
					displayId,
					ScreenshotAutoScrollMaxSteps.coerceAtLeast(1),
					ScreenshotAutoScrollManual,
				).start()
			} catch (t: Throwable) {
				AutoScrollCapture.running = false
				log(t)
			}
		}, SHELF_DISMISS_DELAY)
	}

	/** IntentSender the chooser fires once a share target is picked for [uri]. */
	private fun sharedScreenshotSender(uri: Uri): PendingIntent {
		if (!sharedReceiverRegistered) {
			mContext.registerReceiver(object : BroadcastReceiver() {
				override fun onReceive(context: Context, intent: Intent) {
					val shared = intent.getParcelableExtra(EXTRA_SHARED_URI, Uri::class.java) ?: return
					val delay = ScreenshotDeleteSharedDelay.coerceAtLeast(0) * 1000L
					mainHandler.postDelayed({ deleteSharedScreenshot(shared) }, delay)
				}
			}, IntentFilter(ACTION_SCREENSHOT_SHARED), Context.RECEIVER_NOT_EXPORTED)
			sharedReceiverRegistered = true
		}
		// Mutable so the chooser can fill in the picked component; the package
		// keeps it explicit, as mutable PendingIntents have to be.
		val intent = Intent(ACTION_SCREENSHOT_SHARED)
			.setPackage(mContext.packageName)
			.putExtra(EXTRA_SHARED_URI, uri)
		return PendingIntent.getBroadcast(
			mContext,
			sharedRequestCode++,
			intent,
			PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_UPDATE_CURRENT,
		)
	}

	/** SystemUI inserted the MediaStore row itself, so it may delete it without a consent dialog. */
	private fun deleteSharedScreenshot(uri: Uri) {
		Thread {
			try {
				mContext.contentResolver.delete(uri, null, null)
			} catch (t: Throwable) {
				log(t)
			}
		}.start()
	}

	//Seems like an executor, but doesn't act! perfect thing
	private class NoExecutor : ExecutorService {
		override fun shutdown() {
		}

		override fun shutdownNow(): MutableList<Runnable>? {
			return null
		}

		override fun isShutdown(): Boolean {
			return false
		}

		override fun isTerminated(): Boolean {
			return false
		}

		override fun awaitTermination(l: Long, timeUnit: TimeUnit): Boolean {
			return false
		}

		override fun <T> submit(callable: Callable<T>): Future<T>? {
			return null
		}

		override fun <T> submit(runnable: Runnable, t: T): Future<T>? {
			return null
		}

		override fun submit(runnable: Runnable): Future<*>? {
			return null
		}

		override fun <T> invokeAll(collection: MutableCollection<out Callable<T>>): MutableList<Future<T>>? {
			return null
		}

		override fun <T> invokeAll(collection: MutableCollection<out Callable<T>>, l: Long, timeUnit: TimeUnit): MutableList<Future<T>>? {
			return null
		}

		override fun <T> invokeAny(collection: MutableCollection<out Callable<T>>): T? {
			return null
		}

		override fun <T> invokeAny(collection: MutableCollection<out Callable<T>>, l: Long, timeUnit: TimeUnit): T? {
			return null
		}

		override fun execute(runnable: Runnable) {
		}
	}
}
