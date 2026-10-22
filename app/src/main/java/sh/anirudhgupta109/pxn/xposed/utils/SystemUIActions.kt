package sh.anirudhgupta109.pxn.xposed.utils

import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.input.InputManager
import android.media.AudioManager
import android.os.SystemClock
import android.os.UserHandle
import android.provider.MediaStore
import android.provider.Settings
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.widget.Toast
import de.robv.android.xposed.XposedHelpers.callMethod
import de.robv.android.xposed.XposedHelpers.callStaticMethod
import de.robv.android.xposed.XposedHelpers.findClass
import de.robv.android.xposed.XposedHelpers.getIntField
import de.robv.android.xposed.XposedHelpers.getStaticObjectField
import sh.anirudhgupta109.pxn.Constants
import sh.anirudhgupta109.pxn.R
import sh.anirudhgupta109.pxn.xposed.XPLauncher

/**
 * The actions a gesture can be bound to, run from inside SystemUI.
 *
 * The codes are the values of `R.array.edge_action_codes`; the ones the
 * launcher gestures already had (`custom_action_codes`) keep their number and
 * meaning, so both lists read the same in the preferences file.
 *
 * SystemUI holds the permissions these need (injecting input, expanding the
 * shade, stopping packages, reordering tasks), so most actions are one direct
 * call. Navigation keys are injected the way the 3-button navigation bar
 * injects them, which leaves the framework to route them - back to the
 * focused window, app switch to Recents, SysRq to the screenshot chord.
 * Every action swallows its own failure: a build that moved an API loses
 * that one action, never the gesture handler.
 */
object SystemUIActions {
	const val NO_ACTION = -1
	const val ACTION_SCREENSHOT = 1
	const val ACTION_BACK = 2
	const val ACTION_KILL_APP = 3
	const val ACTION_NOTIFICATION = 4
	const val ACTION_SLEEP = 7
	const val ACTION_SWITCH_APP_PROFILE = 8
	const val ACTION_HOME = 10
	const val ACTION_RECENTS = 11
	const val ACTION_PREVIOUS_APP = 12
	const val ACTION_QUICK_SETTINGS = 13
	const val ACTION_POWER_MENU = 14
	const val ACTION_TORCH = 15
	const val ACTION_AUTO_ROTATE = 16
	const val ACTION_ASSISTANT = 17
	const val ACTION_PLAY_PAUSE = 18
	const val ACTION_MEDIA_NEXT = 19
	const val ACTION_MEDIA_PREVIOUS = 20
	const val ACTION_VOLUME_PANEL = 21
	const val ACTION_CAMERA = 22

	/** `InputManager.INJECT_INPUT_EVENT_MODE_ASYNC` */
	private const val INJECT_MODE_ASYNC = 0

	/** Reads an action preference; anything unparsable means no action. */
	@JvmStatic
	fun parse(value: String?): Int =
		value?.trim()?.toIntOrNull() ?: NO_ACTION

	@JvmStatic
	fun run(context: Context, action: Int, animateTorch: Boolean = false) {
		try {
			when (action) {
				ACTION_BACK -> injectKey(context, KeyEvent.KEYCODE_BACK)
				ACTION_HOME -> injectKey(context, KeyEvent.KEYCODE_HOME)
				ACTION_RECENTS -> injectKey(context, KeyEvent.KEYCODE_APP_SWITCH)
				ACTION_SCREENSHOT -> injectKey(context, KeyEvent.KEYCODE_SYSRQ)
				ACTION_ASSISTANT -> injectKey(context, KeyEvent.KEYCODE_ASSIST)
				ACTION_PREVIOUS_APP -> switchToPreviousApp(context)
				ACTION_KILL_APP -> killForegroundApp(context)
				ACTION_NOTIFICATION -> callMethod(context.getSystemService("statusbar"), "expandNotificationsPanel")
				ACTION_QUICK_SETTINGS -> callMethod(context.getSystemService("statusbar"), "expandSettingsPanel")
				ACTION_POWER_MENU -> showPowerMenu()
				ACTION_SLEEP -> SystemUtils.sleep()
				ACTION_SWITCH_APP_PROFILE -> context.sendBroadcast(Constants.getAppProfileSwitchIntent())
				ACTION_TORCH -> SystemUtils.toggleFlash(animateTorch)
				ACTION_AUTO_ROTATE -> toggleAutoRotate(context)
				ACTION_PLAY_PAUSE -> sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
				ACTION_MEDIA_NEXT -> sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT)
				ACTION_MEDIA_PREVIOUS -> sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
				ACTION_VOLUME_PANEL -> SystemUtils.AudioManager()?.adjustStreamVolume(
					AudioManager.STREAM_MUSIC,
					AudioManager.ADJUST_SAME,
					AudioManager.FLAG_SHOW_UI,
				)
				ACTION_CAMERA -> launchCamera(context)
			}
		} catch (ignored: Throwable) {
		}
	}

	/** A full key press - down then up - as the navigation bar buttons send it. */
	private fun injectKey(context: Context, keyCode: Int) {
		val downTime = SystemClock.uptimeMillis()
		for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
			val event = KeyEvent(
				downTime,
				SystemClock.uptimeMillis(),
				action,
				keyCode,
				0,
				0,
				KeyCharacterMap.VIRTUAL_KEYBOARD,
				0,
				KeyEvent.FLAG_FROM_SYSTEM or KeyEvent.FLAG_VIRTUAL_HARD_KEY,
				InputDevice.SOURCE_KEYBOARD,
			)
			try {
				callMethod(context.getSystemService(InputManager::class.java), "injectInputEvent", event, INJECT_MODE_ASYNC)
			} catch (ignored: Throwable) {
				callMethod(
					callStaticMethod(findClass("android.hardware.input.InputManagerGlobal", null), "getInstance"),
					"injectInputEvent",
					event,
					INJECT_MODE_ASYNC,
				)
			}
		}
	}

	private fun sendMediaKey(context: Context, keyCode: Int) {
		val audioManager = context.getSystemService(AudioManager::class.java)
		audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
		audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
	}

	private fun foregroundTask(context: Context): ActivityManager.RunningTaskInfo? =
		@Suppress("DEPRECATION")
		context.getSystemService(ActivityManager::class.java).getRunningTasks(1).firstOrNull()

	/** The package of the app in front, or null when there is none. */
	@JvmStatic
	fun foregroundPackage(context: Context): String? =
		try {
			foregroundTask(context)?.topActivity?.packageName
		} catch (ignored: Throwable) {
			null
		}

	/** The package that answers the home intent right now. */
	@JvmStatic
	fun homePackage(context: Context): String? =
		try {
			context.packageManager.resolveActivity(
				Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
				0,
			)?.activityInfo?.packageName
		} catch (ignored: Throwable) {
			null
		}

	/**
	 * Brings back the most recent task that is not the one in front. The
	 * recents list leaves the home task out, so on the home screen that is
	 * simply the last app used.
	 */
	private fun switchToPreviousApp(context: Context) {
		val activityManager = context.getSystemService(ActivityManager::class.java)
		val currentTaskId = foregroundTask(context)?.taskId ?: -1

		@Suppress("DEPRECATION")
		val previous = activityManager
			.getRecentTasks(3, ActivityManager.RECENT_IGNORE_UNAVAILABLE)
			.firstOrNull { it.taskId != currentTaskId }
			?: return

		val taskId = previous.taskId
		try {
			callMethod(
				callStaticMethod(findClass("android.app.ActivityTaskManager", null), "getService"),
				"startActivityFromRecents",
				taskId,
				ActivityOptions.makeBasic().toBundle(),
			)
		} catch (ignored: Throwable) {
			activityManager.moveTaskToFront(taskId, 0)
		}
	}

	private fun killForegroundApp(context: Context) {
		val task = foregroundTask(context) ?: return
		val packageName = task.topActivity?.packageName ?: return

		// Neither the launcher nor SystemUI itself is "an app" to kill.
		if (packageName == homePackage(context) || packageName == context.packageName) return

		callMethod(
			context.getSystemService(ActivityManager::class.java),
			"forceStopPackageAsUser",
			packageName,
			getIntField(task, "userId"),
		)

		val label = try {
			val pm = context.packageManager
			pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
		} catch (ignored: Throwable) {
			packageName
		}
		val text = XPLauncher.moduleResources?.getString(R.string.kill_app_toast, label) ?: label
		Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
	}

	private fun showPowerMenu() {
		callMethod(
			callStaticMethod(findClass("android.view.WindowManagerGlobal", null), "getWindowManagerService"),
			"showGlobalActions",
		)
	}

	/**
	 * Goes through `RotationPolicy` like the quick settings tile, so the
	 * user rotation is kept the way the tile keeps it; the bare setting is
	 * the fallback for a build where the helper changed shape.
	 */
	private fun toggleAutoRotate(context: Context) {
		try {
			val rotationPolicy = findClass("com.android.internal.view.RotationPolicy", null)
			val locked = callStaticMethod(rotationPolicy, "isRotationLocked", context) as Boolean
			try {
				callStaticMethod(rotationPolicy, "setRotationLock", context, !locked, "PixelXpert")
			} catch (ignored: Throwable) {
				callStaticMethod(rotationPolicy, "setRotationLock", context, !locked)
			}
		} catch (ignored: Throwable) {
			val enabled = Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
			Settings.System.putInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (enabled) 0 else 1)
		}
	}

	private fun launchCamera(context: Context) {
		val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
			.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
		callMethod(context, "startActivityAsUser", intent, getStaticObjectField(UserHandle::class.java, "CURRENT"))
	}
}
