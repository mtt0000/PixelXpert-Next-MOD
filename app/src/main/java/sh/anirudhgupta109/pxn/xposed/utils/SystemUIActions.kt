package sh.anirudhgupta109.pxn.xposed.utils

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.NotificationManager
import android.app.UiModeManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.hardware.input.InputManager
import android.location.LocationManager
import android.media.AudioManager
import android.media.MediaScannerConnection
import android.net.ConnectivityManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.nfc.NfcManager
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserHandle
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.TelephonyManager
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Toast
import de.robv.android.xposed.XposedHelpers.callMethod
import de.robv.android.xposed.XposedHelpers.callStaticMethod
import de.robv.android.xposed.XposedHelpers.findClass
import de.robv.android.xposed.XposedHelpers.getIntField
import de.robv.android.xposed.XposedHelpers.getObjectField
import de.robv.android.xposed.XposedHelpers.getStaticObjectField
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import sh.anirudhgupta109.pxn.Constants
import sh.anirudhgupta109.pxn.R
import sh.anirudhgupta109.pxn.xposed.XPLauncher

/**
 * The actions a gesture can be bound to, run from inside SystemUI.
 *
 * An action is stored as a string: one of the codes below (the values of
 * `R.array.edge_action_codes`), `app:<package>` to open an app, or
 * `intent:<uri>` for a shortcut or any other intent in `Intent.toUri` form.
 * The codes the launcher gestures already had (`custom_action_codes`) keep
 * their number and meaning, so both lists read the same in the preferences.
 *
 * SystemUI holds the permissions most of these need (injecting input,
 * expanding the shade, stopping packages, reordering tasks, toggling radios),
 * so they are direct calls. Navigation keys are injected the way the 3-button
 * navigation bar injects them, which leaves the framework to route them. What
 * SystemUI cannot do itself - recording the screen, rebooting - goes to the
 * root proxy. The few actions that need the edge windows (tap through, the
 * scrolls, disabling the strips, the blackout) are run by the edge gestures
 * pack itself; [run] reports them back as not handled.
 *
 * Every action swallows its own failure: a build that moved an API loses
 * that one action, never the gesture handler.
 */
object SystemUIActions {
	const val NO_ACTION = -1
	const val ACTION_SCREENSHOT = 1
	const val ACTION_BACK = 2
	const val ACTION_KILL_APP = 3
	const val ACTION_NOTIFICATION = 4
	const val ACTION_ONE_HANDED = 5
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
	const val ACTION_SCREEN_RECORD = 23
	const val ACTION_SPLIT_SCREEN = 24
	const val ACTION_TAP_THROUGH = 25
	const val ACTION_SCROLL_TOP = 26
	const val ACTION_SCROLL_BOTTOM = 27
	const val ACTION_DISABLE_5S = 28
	const val ACTION_DISABLE_UNTIL_TOUCH = 29
	const val ACTION_BLACKOUT = 30
	const val ACTION_VOICE_COMMAND = 31
	const val ACTION_SEARCH = 32
	const val ACTION_PASTE = 33
	const val ACTION_APP_INFO = 34
	const val ACTION_INTERNET_PANEL = 35
	const val ACTION_WIFI_PANEL = 36
	const val ACTION_NFC_PANEL = 37
	const val ACTION_RINGTONE_VOLUME = 38
	const val ACTION_NOTIFICATION_VOLUME = 39
	const val ACTION_SYSTEM_VOLUME = 40
	const val ACTION_FORWARD = 41
	const val ACTION_TOGGLE_WIFI = 42
	const val ACTION_TOGGLE_BLUETOOTH = 43
	const val ACTION_TOGGLE_MOBILE_DATA = 44
	const val ACTION_TOGGLE_AIRPLANE = 45
	const val ACTION_TOGGLE_DND = 46
	const val ACTION_TOGGLE_NFC = 47
	const val ACTION_TOGGLE_LOCATION = 48
	const val ACTION_TOGGLE_DARK_MODE = 49
	const val ACTION_TOGGLE_AUTO_BRIGHTNESS = 50
	const val ACTION_RINGER_MODE = 51
	const val ACTION_CLEAR_NOTIFICATIONS = 52
	const val ACTION_CLEAR_RECENTS = 53
	const val ACTION_RESTART_SYSTEMUI = 54
	const val ACTION_REBOOT = 55
	const val ACTION_POWER_OFF = 56
	const val ACTION_REBOOT_RECOVERY = 57
	const val ACTION_REBOOT_BOOTLOADER = 58
	const val ACTION_KEEP_AWAKE = 59
	const val ACTION_VOLUME_UP = 60
	const val ACTION_VOLUME_DOWN = 61
	const val ACTION_TOGGLE_MUTE = 62
	const val ACTION_FIND_IN_APP = 63

	/** Actions only the edge gestures pack can run, because they need its windows. */
	val EDGE_ACTIONS = setOf(
		ACTION_TAP_THROUGH, ACTION_SCROLL_TOP, ACTION_SCROLL_BOTTOM,
		ACTION_DISABLE_5S, ACTION_DISABLE_UNTIL_TOUCH, ACTION_BLACKOUT, ACTION_KEEP_AWAKE,
	)

	const val APP_PREFIX = "app:"
	const val INTENT_PREFIX = "intent:"

	/** `InputManager.INJECT_INPUT_EVENT_MODE_ASYNC` */
	private const val INJECT_MODE_ASYNC = 0

	/** `Settings.Secure.ONE_HANDED_MODE_ENABLED` */
	private const val ONE_HANDED_MODE_ENABLED = "one_handed_mode_enabled"

	private const val RECORD_PATH = "Movies/PixelXpert"

	private val mainHandler = Handler(Looper.getMainLooper())

	/** Set by the pack that owns the WM shell instances, once it sees them. */
	@JvmStatic
	var splitScreenController: Any? = null

	@JvmStatic
	var oneHandedController: Any? = null

	/** The numeric code of a stored action, or [NO_ACTION] for anything else. */
	@JvmStatic
	fun code(value: String?): Int =
		value?.trim()?.toIntOrNull() ?: NO_ACTION

	/** Whether a stored action does anything at all. */
	@JvmStatic
	fun isBound(value: String?): Boolean {
		val v = value?.trim() ?: return false
		return v.startsWith(APP_PREFIX) || v.startsWith(INTENT_PREFIX) || code(v) != NO_ACTION
	}

	/**
	 * Runs a stored action. Returns false for the ones in [EDGE_ACTIONS],
	 * which the caller has to run itself.
	 */
	@JvmStatic
	fun run(context: Context, value: String, animateTorch: Boolean = false): Boolean {
		val v = value.trim()
		try {
			when {
				v.startsWith(APP_PREFIX) -> launchApp(context, v.removePrefix(APP_PREFIX))
				v.startsWith(INTENT_PREFIX) -> launchIntent(context, v.removePrefix(INTENT_PREFIX))
				else -> {
					val action = code(v)
					if (action in EDGE_ACTIONS) return false
					runCode(context, action, animateTorch)
				}
			}
		} catch (ignored: Throwable) {
		}
		return true
	}

	private fun runCode(context: Context, action: Int, animateTorch: Boolean) {
		when (action) {
			ACTION_BACK -> injectKey(context, KeyEvent.KEYCODE_BACK)
			ACTION_HOME -> injectKey(context, KeyEvent.KEYCODE_HOME)
			ACTION_RECENTS -> injectKey(context, KeyEvent.KEYCODE_APP_SWITCH)
			ACTION_SCREENSHOT -> injectKey(context, KeyEvent.KEYCODE_SYSRQ)
			ACTION_ASSISTANT -> injectKey(context, KeyEvent.KEYCODE_ASSIST)
			ACTION_VOICE_COMMAND -> injectKey(context, KeyEvent.KEYCODE_VOICE_ASSIST)
			ACTION_SEARCH -> injectKey(context, KeyEvent.KEYCODE_SEARCH)
			ACTION_PASTE -> injectKey(context, KeyEvent.KEYCODE_PASTE)
			ACTION_FORWARD -> injectKey(context, KeyEvent.KEYCODE_FORWARD)
			ACTION_FIND_IN_APP -> injectCtrlKey(context, KeyEvent.KEYCODE_F)
			ACTION_PREVIOUS_APP -> switchToPreviousApp(context)
			ACTION_KILL_APP -> killForegroundApp(context)
			ACTION_NOTIFICATION -> callMethod(context.getSystemService("statusbar"), "expandNotificationsPanel")
			ACTION_QUICK_SETTINGS -> callMethod(context.getSystemService("statusbar"), "expandSettingsPanel")
			ACTION_POWER_MENU -> callMethod(windowManagerService(), "showGlobalActions")
			ACTION_SLEEP -> SystemUtils.sleep()
			ACTION_SWITCH_APP_PROFILE -> context.sendBroadcast(Constants.getAppProfileSwitchIntent())
			ACTION_ONE_HANDED -> toggleOneHanded(context)
			ACTION_TORCH -> SystemUtils.toggleFlash(animateTorch)
			ACTION_AUTO_ROTATE -> toggleAutoRotate(context)
			ACTION_PLAY_PAUSE -> sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
			ACTION_MEDIA_NEXT -> sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT)
			ACTION_MEDIA_PREVIOUS -> sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
			ACTION_VOLUME_PANEL -> showVolume(context, AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME)
			ACTION_RINGTONE_VOLUME -> showVolume(context, AudioManager.STREAM_RING, AudioManager.ADJUST_SAME)
			ACTION_NOTIFICATION_VOLUME -> showVolume(context, AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_SAME)
			ACTION_SYSTEM_VOLUME -> showVolume(context, AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_SAME)
			ACTION_VOLUME_UP -> showVolume(context, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.ADJUST_RAISE)
			ACTION_VOLUME_DOWN -> showVolume(context, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.ADJUST_LOWER)
			ACTION_TOGGLE_MUTE -> SystemUtils.toggleMute()
			ACTION_CAMERA -> startActivity(context, Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
			ACTION_APP_INFO -> foregroundPackage(context)?.let {
				startActivity(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$it")))
			}
			ACTION_INTERNET_PANEL -> startActivity(context, Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
			ACTION_WIFI_PANEL -> startActivity(context, Intent(Settings.Panel.ACTION_WIFI))
			ACTION_NFC_PANEL -> startActivity(context, Intent(Settings.Panel.ACTION_NFC))
			ACTION_SCREEN_RECORD -> toggleScreenRecord(context)
			ACTION_SPLIT_SCREEN -> toggleSplitScreen(context)
			ACTION_TOGGLE_WIFI -> toggleWifi(context)
			ACTION_TOGGLE_BLUETOOTH -> toggleBluetooth(context)
			ACTION_TOGGLE_MOBILE_DATA -> toggleMobileData(context)
			ACTION_TOGGLE_AIRPLANE -> toggleAirplane(context)
			ACTION_TOGGLE_DND -> toggleDnd(context)
			ACTION_TOGGLE_NFC -> toggleNfc(context)
			ACTION_TOGGLE_LOCATION -> toggleLocation(context)
			ACTION_TOGGLE_DARK_MODE -> toggleDarkMode(context)
			ACTION_TOGGLE_AUTO_BRIGHTNESS -> toggleAutoBrightness(context)
			ACTION_RINGER_MODE -> cycleRingerMode(context)
			ACTION_CLEAR_NOTIFICATIONS -> clearNotifications()
			ACTION_CLEAR_RECENTS -> callMethod(activityTaskManager(), "removeAllVisibleRecentTasks")
			ACTION_RESTART_SYSTEMUI -> SystemUtils.restart("systemui")
			ACTION_REBOOT -> runRoot("svc power reboot")
			ACTION_POWER_OFF -> runRoot("svc power shutdown")
			ACTION_REBOOT_RECOVERY -> runRoot("svc power reboot recovery")
			ACTION_REBOOT_BOOTLOADER -> SystemUtils.restart("bootloader")
		}
	}

	// ------------------------------------------------------------ input

	/** A full key press - down then up - as the navigation bar buttons send it. */
	@JvmStatic
	fun injectKey(context: Context, keyCode: Int, metaState: Int = 0) {
		val downTime = SystemClock.uptimeMillis()
		for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
			inject(
				context,
				KeyEvent(
					downTime,
					SystemClock.uptimeMillis(),
					action,
					keyCode,
					0,
					metaState,
					KeyCharacterMap.VIRTUAL_KEYBOARD,
					0,
					KeyEvent.FLAG_FROM_SYSTEM or KeyEvent.FLAG_VIRTUAL_HARD_KEY,
					InputDevice.SOURCE_KEYBOARD,
				),
			)
		}
	}

	/**
	 * Ctrl+[keyCode] as a hardware keyboard types it - Ctrl down, the key down
	 * and up with the Ctrl meta state, Ctrl up - so apps that handle keyboard
	 * shortcuts (Ctrl+F: find in page / in app) see a real chord.
	 */
	@JvmStatic
	fun injectCtrlKey(context: Context, keyCode: Int) {
		val downTime = SystemClock.uptimeMillis()
		val ctrl = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
		fun key(action: Int, code: Int, meta: Int) = KeyEvent(
			downTime,
			SystemClock.uptimeMillis(),
			action,
			code,
			0,
			meta,
			KeyCharacterMap.VIRTUAL_KEYBOARD,
			0,
			KeyEvent.FLAG_FROM_SYSTEM,
			InputDevice.SOURCE_KEYBOARD,
		)
		inject(context, key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, ctrl))
		inject(context, key(KeyEvent.ACTION_DOWN, keyCode, ctrl))
		inject(context, key(KeyEvent.ACTION_UP, keyCode, ctrl))
		inject(context, key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT, 0))
	}

	@JvmStatic
	fun inject(context: Context, event: InputEvent) {
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

	/** One finger touching the screen at a point, as the touchscreen reports it. */
	@JvmStatic
	fun touchEvent(downTime: Long, action: Int, x: Float, y: Float): MotionEvent =
		MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0).apply {
			source = InputDevice.SOURCE_TOUCHSCREEN
		}

	/**
	 * Drags a finger from one point to another over [durationMs]. Blocks, so
	 * it belongs on a background thread.
	 */
	@JvmStatic
	fun injectSwipe(context: Context, fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long) {
		val downTime = SystemClock.uptimeMillis()
		inject(context, touchEvent(downTime, MotionEvent.ACTION_DOWN, fromX, fromY))
		val steps = maxOf(2, (durationMs / 8).toInt())
		for (i in 1..steps) {
			val f = i / steps.toFloat()
			SystemUtils.threadSleep((durationMs / steps).toInt())
			inject(context, touchEvent(downTime, MotionEvent.ACTION_MOVE, fromX + (toX - fromX) * f, fromY + (toY - fromY) * f))
		}
		inject(context, touchEvent(downTime, MotionEvent.ACTION_UP, toX, toY))
	}

	private fun sendMediaKey(context: Context, keyCode: Int) {
		val audioManager = context.getSystemService(AudioManager::class.java)
		audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
		audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
	}

	private fun showVolume(context: Context, stream: Int, direction: Int) {
		val audioManager = context.getSystemService(AudioManager::class.java)
		if (stream == AudioManager.USE_DEFAULT_STREAM_TYPE) {
			audioManager.adjustSuggestedStreamVolume(direction, stream, AudioManager.FLAG_SHOW_UI)
		} else {
			audioManager.adjustStreamVolume(stream, direction, AudioManager.FLAG_SHOW_UI)
		}
	}

	// ------------------------------------------------------------ tasks

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

	private fun activityTaskManager(): Any =
		callStaticMethod(findClass("android.app.ActivityTaskManager", null), "getService")

	private fun windowManagerService(): Any =
		callStaticMethod(findClass("android.view.WindowManagerGlobal", null), "getWindowManagerService")

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

		try {
			callMethod(activityTaskManager(), "startActivityFromRecents", previous.taskId, ActivityOptions.makeBasic().toBundle())
		} catch (ignored: Throwable) {
			activityManager.moveTaskToFront(previous.taskId, 0)
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

		toast(context, string(R.string.kill_app_toast, labelOf(context, packageName)))
	}

	/**
	 * Split screen goes through the WM shell's own controller, which lives in
	 * SystemUI: the app in front moves to one half and the launcher offers
	 * the other. Run again while split, it leaves split screen keeping the
	 * app in front. Without the controller, the shell's command line does the
	 * same through root.
	 */
	private fun toggleSplitScreen(context: Context) {
		val taskId = foregroundTask(context)?.taskId ?: return
		val controller = splitScreenController

		if (controller != null) {
			val visible = try {
				callMethod(controller, "isSplitScreenVisible") as Boolean
			} catch (ignored: Throwable) {
				false
			}
			try {
				if (visible) {
					callMethod(controller, "exitSplitScreen", taskId, 0)
				} else {
					callMethod(controller, "moveToSideStage", taskId, 0)
				}
				return
			} catch (ignored: Throwable) {
			}
		}

		runRoot("dumpsys activity service SystemUIService WMShell splitscreen moveToSideStage $taskId 0")
	}

	/**
	 * One-handed mode through the WM shell's controller. The feature has to
	 * be switched on in Settings for the controller to act, so the action
	 * switches it on the first time it is used.
	 */
	private fun toggleOneHanded(context: Context) {
		val controller = oneHandedController ?: return
		if (Settings.Secure.getInt(context.contentResolver, ONE_HANDED_MODE_ENABLED, 0) != 1) {
			Settings.Secure.putInt(context.contentResolver, ONE_HANDED_MODE_ENABLED, 1)
		}

		val active = try {
			callMethod(getObjectField(controller, "mState"), "getState") as Int != 0
		} catch (ignored: Throwable) {
			false
		}
		mainHandler.post {
			try {
				callMethod(controller, if (active) "stopOneHanded" else "startOneHanded")
			} catch (ignored: Throwable) {
			}
		}
	}

	// ------------------------------------------------------------ launching

	private fun currentUser(): UserHandle =
		getStaticObjectField(UserHandle::class.java, "CURRENT") as UserHandle

	private fun startActivity(context: Context, intent: Intent) {
		intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
		callMethod(context, "startActivityAsUser", intent, currentUser())
		try {
			callMethod(context.getSystemService("statusbar"), "collapsePanels")
		} catch (ignored: Throwable) {
		}
	}

	private fun launchApp(context: Context, packageName: String) {
		val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return
		startActivity(context, intent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
	}

	private fun launchIntent(context: Context, uri: String) {
		val intent = Intent.parseUri(uri, if (uri.startsWith("intent:")) Intent.URI_INTENT_SCHEME else 0)
		try {
			startActivity(context, intent)
		} catch (t: Throwable) {
			// Not an activity: some shortcuts are broadcasts.
			intent.flags = intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK.inv()
			context.sendBroadcast(intent)
		}
	}

	// ------------------------------------------------------------ system toggles

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
			toggleSystemSetting(context, Settings.System.ACCELEROMETER_ROTATION)
		}
	}

	private fun toggleAutoBrightness(context: Context) {
		toggleSystemSetting(context, Settings.System.SCREEN_BRIGHTNESS_MODE)
	}

	private fun toggleSystemSetting(context: Context, name: String) {
		val enabled = Settings.System.getInt(context.contentResolver, name, 0) == 1
		Settings.System.putInt(context.contentResolver, name, if (enabled) 0 else 1)
	}

	@Suppress("DEPRECATION")
	private fun toggleWifi(context: Context) {
		val wifiManager = context.getSystemService(WifiManager::class.java)
		wifiManager.isWifiEnabled = !wifiManager.isWifiEnabled
	}

	@SuppressLint("MissingPermission")
	private fun toggleBluetooth(context: Context) {
		val adapter = context.getSystemService(BluetoothManager::class.java).adapter ?: return
		@Suppress("DEPRECATION")
		if (adapter.isEnabled) adapter.disable() else adapter.enable()
	}

	@SuppressLint("MissingPermission")
	private fun toggleMobileData(context: Context) {
		val telephonyManager = context.getSystemService(TelephonyManager::class.java)
		val enabled = telephonyManager.isDataEnabled
		try {
			telephonyManager.setDataEnabledForReason(TelephonyManager.DATA_ENABLED_REASON_USER, !enabled)
		} catch (ignored: Throwable) {
			runRoot("svc data " + if (enabled) "disable" else "enable")
		}
	}

	private fun toggleAirplane(context: Context) {
		val enabled = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
		try {
			callMethod(context.getSystemService(ConnectivityManager::class.java), "setAirplaneMode", !enabled)
		} catch (ignored: Throwable) {
			runRoot("cmd connectivity airplane-mode " + if (enabled) "disable" else "enable")
		}
	}

	private fun toggleDnd(context: Context) {
		val notificationManager = context.getSystemService(NotificationManager::class.java)
		val on = notificationManager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
		// Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS = 1, ZEN_MODE_OFF = 0
		val mode = if (on) 0 else 1
		try {
			callMethod(notificationManager, "setZenMode", mode, null, "PixelXpert", true)
		} catch (ignored: Throwable) {
			try {
				callMethod(notificationManager, "setZenMode", mode, null, "PixelXpert")
			} catch (ignored2: Throwable) {
				runRoot("cmd notification set_dnd " + if (on) "off" else "on")
			}
		}
	}

	private fun toggleNfc(context: Context) {
		val adapter = context.getSystemService(NfcManager::class.java)?.defaultAdapter ?: return
		try {
			callMethod(adapter, if (adapter.isEnabled) "disable" else "enable")
		} catch (ignored: Throwable) {
			runRoot("svc nfc " + if (adapter.isEnabled) "disable" else "enable")
		}
	}

	private fun toggleLocation(context: Context) {
		val locationManager = context.getSystemService(LocationManager::class.java)
		val enabled = locationManager.isLocationEnabled
		try {
			callMethod(locationManager, "setLocationEnabledForUser", !enabled, currentUser())
		} catch (ignored: Throwable) {
			runRoot("cmd location set-location-enabled " + !enabled)
		}
	}

	private fun toggleDarkMode(context: Context) {
		val uiModeManager = context.getSystemService(UiModeManager::class.java)
		val night = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
			android.content.res.Configuration.UI_MODE_NIGHT_YES
		try {
			callMethod(uiModeManager, "setNightModeActivated", !night)
		} catch (ignored: Throwable) {
			runRoot("cmd uimode night " + if (night) "no" else "yes")
		}
	}

	private fun cycleRingerMode(context: Context) {
		val audioManager = context.getSystemService(AudioManager::class.java)
		val next = when (audioManager.ringerMode) {
			AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
			AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_SILENT
			else -> AudioManager.RINGER_MODE_NORMAL
		}
		try {
			callMethod(audioManager, "setRingerModeInternal", next)
		} catch (ignored: Throwable) {
			audioManager.ringerMode = next
		}
	}

	private fun clearNotifications() {
		val binder = callStaticMethod(findClass("android.os.ServiceManager", null), "getService", "statusbar")
		val service = callStaticMethod(findClass("com.android.internal.statusbar.IStatusBarService\$Stub", null), "asInterface", binder)
		callMethod(service, "onClearAllNotifications", callStaticMethod(ActivityManager::class.java, "getCurrentUser"))
	}

	// ------------------------------------------------------------ root

	/**
	 * The proxy runs a command on the calling thread when it is connected,
	 * so every root command leaves the main thread first.
	 */
	private fun runRoot(command: String, then: (() -> Unit)? = null) {
		Thread {
			XPLauncher.enqueueProxyCommand { proxy ->
				try {
					proxy.runRootCommand(command)
				} catch (ignored: Throwable) {
				}
				then?.invoke()
			}
		}.start()
	}

	@Volatile
	private var recordingFile: String? = null

	/**
	 * Screen recording through the `screenrecord` binary under root, so it
	 * needs no confirmation dialog: the first run starts it in the
	 * background, the next one stops it with SIGINT - which lets the binary
	 * finish the MP4 - and hands the file to the media scanner.
	 */
	private fun toggleScreenRecord(context: Context) {
		val current = recordingFile
		if (current != null) {
			recordingFile = null
			runRoot("pkill -INT -x screenrecord; sleep 2") {
				MediaScannerConnection.scanFile(context, arrayOf(current), arrayOf("video/mp4"), null)
				toast(context, string(R.string.edge_record_saved, current))
			}
			return
		}

		// The root shell sees shared storage as /sdcard; the media scanner
		// wants the path apps see.
		val name = "screen_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
		val shellFile = "/sdcard/$RECORD_PATH/$name"
		recordingFile = File(Environment.getExternalStorageDirectory(), "$RECORD_PATH/$name").absolutePath
		runRoot(
			"mkdir -p '/sdcard/$RECORD_PATH'; " +
				"(nohup screenrecord --time-limit 0 '$shellFile' >/dev/null 2>&1 || " +
				"nohup screenrecord '$shellFile' >/dev/null 2>&1) &",
		)
		toast(context, string(R.string.edge_record_started))
	}

	// ------------------------------------------------------------ helpers

	private fun labelOf(context: Context, packageName: String): String =
		try {
			val pm = context.packageManager
			pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
		} catch (ignored: Throwable) {
			packageName
		}

	@JvmStatic
	fun string(id: Int, vararg args: Any): String =
		try {
			XPLauncher.moduleResources!!.getString(id, *args)
		} catch (ignored: Throwable) {
			""
		}

	@JvmStatic
	fun toast(context: Context, text: String) {
		if (text.isEmpty()) return
		mainHandler.post { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
	}
}
