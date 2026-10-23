package sh.anirudhgupta109.pxn.xposed.modpacks.android

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationAttributes.USAGE_ACCESSIBILITY
import android.os.VibrationEffect.EFFECT_TICK
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_POWER
import android.view.KeyEvent.KEYCODE_VOLUME_DOWN
import android.view.KeyEvent.KEYCODE_VOLUME_UP
import android.view.ViewConfiguration
import de.robv.android.xposed.XposedHelpers.callMethod
import de.robv.android.xposed.XposedHelpers.getIntField
import de.robv.android.xposed.XposedHelpers.getObjectField
import de.robv.android.xposed.XposedHelpers.setObjectField
import io.github.libxposed.api.XposedModuleInterface
import org.apache.commons.lang3.SystemProperties
import sh.anirudhgupta109.pxn.Constants
import sh.anirudhgupta109.pxn.util.KeyRemapMigration
import sh.anirudhgupta109.pxn.xposed.XPrefs.Xprefs
import sh.anirudhgupta109.pxn.xposed.XposedModPack
import sh.anirudhgupta109.pxn.xposed.annotations.FrameworkModPack
import sh.anirudhgupta109.pxn.xposed.utils.SystemUIActions
import sh.anirudhgupta109.pxn.xposed.utils.SystemUtils
import sh.anirudhgupta109.pxn.xposed.utils.reflection.ReflectedClass
import sh.anirudhgupta109.pxn.xposed.utils.reflection.ReflectedMethod

/**
 * Remaps the physical keys (Settings > Physical buttons): Power long and
 * double press, Volume up / down single, double and long press, each with
 * the screen on and off, and the two-key combinations. Every one holds a
 * gesture action code, "-1" (Android's own behaviour) or "0" (do nothing).
 *
 * Power: `PhoneWindowManager$PowerKeyRule.onLongPress` and
 * `GestureLauncherService.handleCameraGesture` (the double press), as
 * before. A single press is left alone - it is what wakes and locks the
 * phone. `startedWakingUp` only timestamps `mWakeTime`: whether the screen
 * counts as on is uptime minus that stamp, because the display state lags
 * the very power press being judged.
 *
 * Volume: `interceptKeyBeforeQueueing` takes a press whenever that key has
 * anything mapped for the current screen state, and decides on its own:
 * a long press after the long-press timeout, a double press when a second
 * press starts within the double-tap timeout (only waited for when a double
 * press is mapped), otherwise a single press. A press that ends up with no
 * mapping is replayed as an injected key - held, too, with key repeats - so
 * Android does what it always does; injected keys pass straight through.
 * During calls and ringing (audio mode not normal) the keys are left alone.
 *
 * Combinations are recognised by the input service before the policy sees
 * the keys (KeyGestureController on 17, PhoneWindowManager before), which
 * flags the keys with FLAG_KEY_GESTURE_TRIGGERED; then any pending volume
 * press is dropped. A mapped combination replaces the rule's `execute` (and
 * its `cancel`), and its `preCondition` is forced true, so e.g. Volume up +
 * down works without the accessibility shortcut configured.
 *
 * Torch, camera, assistant and media keys run here, as before (the camera
 * and the assistant then start from the lock screen); every other action is
 * sent to SystemUI, which runs it with SystemUIActions (KeyActionReceiver).
 * After a quiet action (torch, media, toggles, nothing) on a power press that
 * woke the screen, the screen goes back off.
 *
 * With `controlFlashWithVolKeys` set and the torch already lit, the volume
 * keys instead step the torch level - one step per press, or a 100ms ramp
 * while held - so the flashlight stays reachable from the pocket.
 */
@FrameworkModPack
class ScreenOffKeys(context: Context) : XposedModPack(context) {

	companion object {
		const val KEY_DEFAULT = "-1"
		const val KEY_NOTHING = "0"

		const val WAKE_REASON_POWER_BUTTON = 1
		const val CAMERA_LAUNCH_SOURCE_POWER_DOUBLE_TAP = 1
		private const val INVOCATION_TYPE_POWER_BUTTON_LONG_PRESS = 6
		const val ACTION_COMPLETE = 1 // SingleKeyGestureEvent

		/** WindowManagerPolicyConstants.FLAG_INJECTED */
		private const val POLICY_FLAG_INJECTED = 0x01000000
		private const val KEY_REPEAT_INTERVAL = 50L

		private val POWER_KEYS = listOf("KeyPowerLongOn", "KeyPowerDoubleOn", "KeyPowerLongOff", "KeyPowerDoubleOff")
		private val VOLUME_KEYS = listOf("KeyVolUp", "KeyVolDown").flatMap { key ->
			listOf("Single", "Double", "Long").flatMap { press -> listOf("$key${press}On", "$key${press}Off") }
		}
		private const val COMBO_POWER_VOL_DOWN = "KeyComboPowerVolDown"
		private const val COMBO_POWER_VOL_UP = "KeyComboPowerVolUp"
		private const val COMBO_VOL_UP_DOWN = "KeyComboVolUpDown"
		private val ALL_KEYS = POWER_KEYS + VOLUME_KEYS + listOf(COMBO_POWER_VOL_DOWN, COMBO_POWER_VOL_UP, COMBO_VOL_UP_DOWN)

		/** New key to the pre-gesture-actions key it replaces, until the app migrates it. */
		private val OLD_KEYS = KeyRemapMigration.KEYS.entries.associate { (old, new) -> new to old }

		/** Actions that need no screen: after one, a power press that woke the screen turns it off again. */
		private val QUIET_ACTIONS = setOf(
			0,
			SystemUIActions.ACTION_TORCH,
			SystemUIActions.ACTION_PLAY_PAUSE,
			SystemUIActions.ACTION_MEDIA_NEXT,
			SystemUIActions.ACTION_MEDIA_PREVIOUS,
			SystemUIActions.ACTION_VOLUME_UP,
			SystemUIActions.ACTION_VOLUME_DOWN,
			SystemUIActions.ACTION_TOGGLE_MUTE,
			SystemUIActions.ACTION_TOGGLE_WIFI,
			SystemUIActions.ACTION_TOGGLE_BLUETOOTH,
			SystemUIActions.ACTION_TOGGLE_MOBILE_DATA,
			SystemUIActions.ACTION_TOGGLE_AIRPLANE,
			SystemUIActions.ACTION_TOGGLE_DND,
			SystemUIActions.ACTION_TOGGLE_NFC,
			SystemUIActions.ACTION_TOGGLE_LOCATION,
			SystemUIActions.ACTION_TOGGLE_DARK_MODE,
			SystemUIActions.ACTION_TOGGLE_AUTO_BRIGHTNESS,
			SystemUIActions.ACTION_RINGER_MODE,
			SystemUIActions.ACTION_AUTO_ROTATE,
		)

		private var AnimateFlashlight = false
		private var controlFlashWithVolKeys = false
	}

	private val keyActions = HashMap<String, String>()

	private var launchAssistActionMethod: ReflectedMethod? = null
	private var windowMan: Any? = null
	private var mWakeTime = 0L
	private var policyHandler: Handler? = null
	private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

	/** WindowManagerPolicyConstants.FLAG_KEY_GESTURE_TRIGGERED, read at runtime; 0 where it does not exist. */
	private var keyGestureTriggeredFlag = 0

	/** Set while our own camera launch calls handleCameraGesture, so its hook stays out. */
	@Volatile
	private var launchingCamera = false

	private val lock = Any()
	private val volumeUp = VolumeKey(KEYCODE_VOLUME_UP, "KeyVolUp")
	private val volumeDown = VolumeKey(KEYCODE_VOLUME_DOWN, "KeyVolDown")
	private var powerDown = false

	/** The combination rule whose execute was replaced, so its cancel is skipped too. */
	private var overriddenRule: Any? = null

	private var mFlashAdjustRunnable = FlashAdjustLongRunnable(KEYCODE_VOLUME_DOWN)
	private val mLock = Any()
	private var mKeyIsDown = false
	private var mLoopRan = false

	@SuppressLint("CheckResult")
	override fun onPreferenceUpdated(vararg key: String?) {
		try {
			for (name in ALL_KEYS) keyActions[name] = readKeyAction(name)

			controlFlashWithVolKeys = Xprefs.getBoolean("controlFlashWithVolKeys", false)

			AnimateFlashlight = Xprefs.getBoolean("AnimateFlashlight", false)
			//noinspection ResultOfMethodCallIgnored
			SystemUtils.CameraManager() //init CameraManager to listen to flash status
		} catch (ignored: Throwable) {
		}
	}

	/** The new key, else the old one it replaces, converted, while the app has not migrated it. */
	private fun readKeyAction(name: String): String {
		val value = try {
			Xprefs.getString(name, null)
		} catch (ignored: Throwable) {
			null
		}
		if (!value.isNullOrBlank()) return value.trim()
		val old = OLD_KEYS[name] ?: return KEY_DEFAULT
		return try {
			KeyRemapMigration.convert(Xprefs.getString(old, null))
		} catch (ignored: Throwable) {
			null
		} ?: KEY_DEFAULT
	}

	private fun action(name: String): String = keyActions[name] ?: KEY_DEFAULT

	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		keyGestureTriggeredFlag = try {
			Class.forName("android.view.WindowManagerPolicyConstants").getField("FLAG_KEY_GESTURE_TRIGGERED").getInt(null)
		} catch (ignored: Throwable) {
			0
		}

		try {
			val PhoneWindowManagerClass = ReflectedClass.of("com.android.server.policy.PhoneWindowManager")
			val PowerKeyRuleClass = ReflectedClass.of("com.android.server.policy.PhoneWindowManager\$PowerKeyRule")
			val GestureLauncherServiceClass = ReflectedClass.of("com.android.server.GestureLauncherService")

			launchAssistActionMethod = ReflectedMethod.ofName(PhoneWindowManagerClass, "launchAssistAction")

			GestureLauncherServiceClass
				.before("handleCameraGesture")
				.run { param ->
					if (launchingCamera) return@run
					val screenIsOn = screenIsOn()
					val action = powerAction("Double", screenIsOn)
					if (action != KEY_DEFAULT) {
						runAction(action, screenIsOn, sleepAfter = true, delaySleep = true)
						param.setResult(true)
					}
				}

			PhoneWindowManagerClass
				.after("enableScreen")
				.run { param ->
					windowMan = param.thisObject
					policyHandler = runCatching { getObjectField(param.thisObject, "mHandler") as Handler }.getOrNull()

					setObjectField(
						getObjectField(param.thisObject, "mGestureLauncherService"),
						"mCameraDoubleTapPowerEnabled",
						true,
					)
				}

			PowerKeyRuleClass
				.before("onLongPress")
				.run { param ->
					var actionType = ACTION_COMPLETE
					try { //TODO: no need to try/catch once QPR1 stable is released
						actionType = callMethod(
							param.args[0],
							"getAction",
						) as Int
					} catch (ignored: Throwable) {
					}

					val screenIsOn = screenIsOn()
					val customAction = powerAction("Long", screenIsOn)

					if (customAction != KEY_DEFAULT) {
						// Suppress OS handling of this long press (both ACTION_START and ACTION_COMPLETE)
						param.setResult(null)

						if (actionType == ACTION_COMPLETE) {
							runAction(customAction, screenIsOn, sleepAfter = true, delaySleep = false)
						}
					}
				}

			PhoneWindowManagerClass
				.before("startedWakingUp")
				.run { param ->
					for (arg in param.args) {
						if (arg is Int && arg == WAKE_REASON_POWER_BUTTON) {
							mWakeTime = SystemClock.uptimeMillis()
							break
						}
					}
				}

			PhoneWindowManagerClass
				.before("interceptKeyBeforeQueueing")
				.run { param ->
					try {
						val event = param.args[0] as KeyEvent
						val policyFlags = param.args.getOrNull(1) as? Int ?: 0
						if (policyHandler == null) {
							policyHandler = runCatching { getObjectField(param.thisObject, "mHandler") as Handler }.getOrNull()
						}
						if (interceptKey(event, policyFlags)) param.setResult(0)
					} catch (ignored: Throwable) {
					}
				}
		} catch (ignored: Throwable) {
		}

		hookCombinationRules()
	}

	// ------------------------------------------------------------ keys

	/** True when the key is consumed. */
	private fun interceptKey(event: KeyEvent, policyFlags: Int): Boolean {
		val keyCode = event.keyCode
		if (keyCode != KEYCODE_POWER && keyCode != KEYCODE_VOLUME_UP && keyCode != KEYCODE_VOLUME_DOWN) return false
		// Our own replays, and keys other apps inject.
		if (policyFlags and POLICY_FLAG_INJECTED != 0) return false

		if (keyCode != KEYCODE_POWER &&
			controlFlashWithVolKeys &&
			SystemUtils.isFlashOn()
		) {
			handleFlashKeys(event)
			return true
		}

		synchronized(lock) {
			if (keyCode == KEYCODE_POWER) {
				powerDown = event.action == ACTION_DOWN
			}

			// A combination took these keys: drop whatever volume press was pending.
			if (keyGestureTriggeredFlag != 0 && policyFlags and keyGestureTriggeredFlag != 0) {
				volumeUp.cancel()
				volumeDown.cancel()
				return false
			}
			if (keyCode == KEYCODE_POWER) return false

			val key = if (keyCode == KEYCODE_VOLUME_UP) volumeUp else volumeDown
			val other = if (keyCode == KEYCODE_VOLUME_UP) volumeDown else volumeUp

			// With Power or the other volume key down this is (probably) a combination.
			if (event.action == ACTION_DOWN && event.repeatCount == 0 && (powerDown || other.isDown)) {
				other.cancel()
				key.passThrough()
				return false
			}
			return key.onEvent(event)
		}
	}

	/** Volume up or down: one press at a time, see the class docs. */
	private inner class VolumeKey(val keyCode: Int, val prefix: String) {
		/** The current press is ours. */
		private var handling = false
		/** The first press ended; a second one would make a double press. */
		private var waitingSecond = false
		private var secondPress = false
		private var longFired = false
		/** A long press with nothing mapped, replayed as a held key. */
		private var holding = false
		private var repeatCount = 0
		/** Taken by a combination: the rest of the press does nothing. */
		private var cancelled = false
		/** Not ours, until the key goes up. */
		private var ignoring = false
		private var interactive = true
		private var downTime = 0L

		var isDown = false
			private set

		private val longRunnable = Runnable { synchronized(lock) { onLong() } }
		private val singleRunnable = Runnable { synchronized(lock) { onSingleTimeout() } }
		private val repeatRunnable = Runnable { synchronized(lock) { onRepeat() } }

		private fun mapped(press: String): String = action("$prefix$press${if (interactive) "On" else "Off"}")

		fun onEvent(event: KeyEvent): Boolean {
			when (event.action) {
				ACTION_DOWN -> {
					isDown = true
					if (event.repeatCount > 0) return handling || holding
					if (waitingSecond) {
						policyHandler?.removeCallbacks(singleRunnable)
						waitingSecond = false
						secondPress = true
						handling = true
						return true
					}
					reset()
					interactive = deviceIsInteractive()
					val handler = policyHandler ?: return false
					if (SystemUtils.AudioManager()?.mode != AudioManager.MODE_NORMAL) return false
					if (mapped("Single") == KEY_DEFAULT && mapped("Double") == KEY_DEFAULT && mapped("Long") == KEY_DEFAULT) {
						return false
					}
					handling = true
					downTime = event.downTime
					handler.postDelayed(longRunnable, ViewConfiguration.getLongPressTimeout().toLong())
					return true
				}

				ACTION_UP -> {
					isDown = false
					if (ignoring) {
						ignoring = false
						return false
					}
					if (!handling) return false
					handling = false
					policyHandler?.removeCallbacks(longRunnable)
					when {
						cancelled -> cancelled = false
						holding -> {
							policyHandler?.removeCallbacks(repeatRunnable)
							holding = false
							inject(ACTION_UP, 0)
						}
						longFired -> longFired = false
						secondPress -> {
							secondPress = false
							run(mapped("Double"))
						}
						mapped("Double") != KEY_DEFAULT -> {
							waitingSecond = true
							policyHandler?.postDelayed(singleRunnable, ViewConfiguration.getDoubleTapTimeout().toLong())
						}
						else -> single()
					}
					return true
				}
			}
			return false
		}

		private fun onLong() {
			if (!handling || cancelled || secondPress) return
			val long = mapped("Long")
			if (long != KEY_DEFAULT) {
				longFired = true
				run(long)
			} else {
				// Nothing mapped: hand Android the key as if it had been held all along.
				holding = true
				repeatCount = 0
				inject(ACTION_DOWN, 0)
				policyHandler?.postDelayed(repeatRunnable, KEY_REPEAT_INTERVAL)
			}
		}

		private fun onRepeat() {
			if (!holding) return
			inject(ACTION_DOWN, ++repeatCount)
			policyHandler?.postDelayed(repeatRunnable, KEY_REPEAT_INTERVAL)
		}

		private fun onSingleTimeout() {
			if (!waitingSecond) return
			waitingSecond = false
			single()
		}

		private fun single() {
			val single = mapped("Single")
			if (single != KEY_DEFAULT) {
				run(single)
			} else {
				inject(ACTION_DOWN, 0)
				inject(ACTION_UP, 0)
			}
		}

		private fun run(value: String) {
			val screenIsOn = interactive
			mainHandler.post { runAction(value, screenIsOn, sleepAfter = false, delaySleep = false) }
		}

		/** A replay of this key, which the hook lets through (it is injected). */
		private fun inject(action: Int, repeat: Int) {
			val now = SystemClock.uptimeMillis()
			SystemUIActions.inject(
				mContext,
				KeyEvent(
					if (downTime > 0) downTime else now,
					now,
					action,
					keyCode,
					repeat,
					0,
					KeyCharacterMap.VIRTUAL_KEYBOARD,
					0,
					KeyEvent.FLAG_FROM_SYSTEM,
					InputDevice.SOURCE_KEYBOARD,
				),
			)
		}

		/** A combination took the key: whatever was pending is dropped. */
		fun cancel() {
			policyHandler?.removeCallbacks(longRunnable)
			policyHandler?.removeCallbacks(singleRunnable)
			policyHandler?.removeCallbacks(repeatRunnable)
			if (holding) {
				holding = false
				inject(ACTION_UP, 0)
			}
			waitingSecond = false
			secondPress = false
			longFired = false
			if (handling) cancelled = true
		}

		/** This press is part of a combination: Android gets it untouched. */
		fun passThrough() {
			cancel()
			handling = false
			cancelled = false
			ignoring = true
			isDown = true
		}

		private fun reset() {
			cancelled = false
			ignoring = false
			longFired = false
			secondPress = false
			holding = false
			repeatCount = 0
		}
	}

	// ------------------------------------------------------------ combinations

	/**
	 * The two-key rules are anonymous subclasses of
	 * KeyCombinationManager.TwoKeysCombinationRule, in KeyGestureController
	 * (input service, 17) or PhoneWindowManager (before).
	 */
	private fun hookCombinationRules() {
		for (owner in arrayOf("com.android.server.input.KeyGestureController", "com.android.server.policy.PhoneWindowManager")) {
			for (i in 1..120) {
				val ruleClass = ReflectedClass.ofIfPossible("$owner\$$i")
				val clazz = ruleClass.clazz ?: continue
				if (clazz.superclass?.name?.endsWith("KeyCombinationManager\$TwoKeysCombinationRule") != true) continue
				try {
					ruleClass
						.before("execute")
						.run { param ->
							val value = comboAction(param.thisObject)
							if (value == KEY_DEFAULT) {
								overriddenRule = null
								return@run
							}
							param.setResult(null)
							overriddenRule = param.thisObject
							val screenIsOn = deviceIsInteractive()
							mainHandler.post { runAction(value, screenIsOn, sleepAfter = false, delaySleep = false) }
						}
					ruleClass
						.before("cancel")
						.run { param ->
							if (param.thisObject === overriddenRule) param.setResult(null)
						}
					ruleClass
						.after("preCondition")
						.run { param ->
							if (comboAction(param.thisObject) != KEY_DEFAULT) param.setResult(true)
						}
				} catch (ignored: Throwable) {
				}
			}
		}
	}

	private fun comboAction(rule: Any?): String {
		val codes = try {
			setOf(getIntField(rule, "mKeyCode1"), getIntField(rule, "mKeyCode2"))
		} catch (ignored: Throwable) {
			return KEY_DEFAULT
		}
		return when (codes) {
			setOf(KEYCODE_POWER, KEYCODE_VOLUME_DOWN) -> action(COMBO_POWER_VOL_DOWN)
			setOf(KEYCODE_POWER, KEYCODE_VOLUME_UP) -> action(COMBO_POWER_VOL_UP)
			setOf(KEYCODE_VOLUME_UP, KEYCODE_VOLUME_DOWN) -> action(COMBO_VOL_UP_DOWN)
			else -> KEY_DEFAULT
		}
	}

	// ------------------------------------------------------------ actions

	/** Power [press] for the screen state; a lit torch keeps the screen-off torch mapping, to turn it off. */
	private fun powerAction(press: String, screenIsOn: Boolean): String {
		val off = action("KeyPower${press}Off")
		if (off.toIntOrNull() == SystemUIActions.ACTION_TORCH && SystemUtils.isFlashOn()) return off
		return action("KeyPower$press${if (screenIsOn) "On" else "Off"}")
	}

	/**
	 * Runs a mapped action. [sleepAfter]: a power press, which woke the
	 * screen; after a quiet action it goes back off (after a moment for a
	 * double press, whose second press woke it).
	 */
	private fun runAction(value: String, screenIsOn: Boolean, sleepAfter: Boolean, delaySleep: Boolean) {
		try {
			val code = value.trim().toIntOrNull()
			when (code) {
				0 -> {}
				SystemUIActions.ACTION_TORCH -> SystemUtils.toggleFlash(AnimateFlashlight)
				SystemUIActions.ACTION_CAMERA -> if (!launchCamera()) sendToSystemUI(value)
				SystemUIActions.ACTION_ASSISTANT -> if (!launchAssistant()) sendToSystemUI(value)
				SystemUIActions.ACTION_PLAY_PAUSE -> dispatchAudioKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
				SystemUIActions.ACTION_MEDIA_NEXT -> if (musicActive()) dispatchAudioKey(KeyEvent.KEYCODE_MEDIA_NEXT)
				SystemUIActions.ACTION_MEDIA_PREVIOUS -> if (musicActive()) dispatchAudioKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
				else -> sendToSystemUI(value)
			}

			if (code != 0) SystemUtils.vibrate(EFFECT_TICK, USAGE_ACCESSIBILITY)

			if (sleepAfter && !screenIsOn && code != null && code in QUIET_ACTIONS) {
				Thread {
					if (delaySleep) SystemUtils.threadSleep(500)
					SystemUtils.sleep()
				}.start()
			}
		} catch (ignored: Throwable) {
		}
	}

	/** Through GestureLauncherService, which can start the secure camera on the lock screen. */
	private fun launchCamera(): Boolean = try {
		launchingCamera = true
		callMethod(getObjectField(windowMan, "mGestureLauncherService"), "handleCameraGesture", false, CAMERA_LAUNCH_SOURCE_POWER_DOUBLE_TAP) as Boolean
	} catch (ignored: Throwable) {
		false
	} finally {
		launchingCamera = false
	}

	private fun launchAssistant(): Boolean = try {
		launchAssistActionMethod!!.invoke(windowMan, null, -2, SystemClock.uptimeMillis(), INVOCATION_TYPE_POWER_BUTTON_LONG_PRESS)
		true
	} catch (ignored: Throwable) {
		false
	}

	/** Everything else runs in SystemUI, see KeyActionReceiver. */
	@SuppressLint("MissingPermission")
	private fun sendToSystemUI(value: String) {
		val intent = Intent(SystemUIActions.ACTION_RUN_KEY_ACTION)
			.setPackage(Constants.SYSTEM_UI_PACKAGE)
			.putExtra(SystemUIActions.EXTRA_KEY_ACTION, value)
			.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
		mContext.sendBroadcast(intent)
	}

	@Suppress("DEPRECATION")
	private fun musicActive(): Boolean = SystemUtils.AudioManager()?.isMusicActive == true

	private fun screenIsOn(): Boolean { //for power button, display state isn't reliable enough because pressing power will trigger it
		return SystemClock.uptimeMillis() - mWakeTime > 1000
	}

	private fun deviceIsInteractive(): Boolean {
		//noinspection DataFlowIssue
		return SystemUtils.PowerManager()!!.isInteractive()
	}

	private fun dispatchAudioKey(keyCode: Int) {
		//noinspection DataFlowIssue
		SystemUtils.AudioManager()!!.dispatchMediaKeyEvent(KeyEvent(ACTION_DOWN, keyCode))

		SystemUtils.AudioManager()!!.dispatchMediaKeyEvent(KeyEvent(ACTION_UP, keyCode))
	}

	// ------------------------------------------------------------ torch

	private fun handleFlashKeys(event: KeyEvent) {
		val handler = policyHandler ?: return
		when (event.action) {
			ACTION_DOWN -> {
				if (event.repeatCount > 0) return
				mFlashAdjustRunnable = FlashAdjustLongRunnable(event.keyCode)
				synchronized(mLock) {
					mKeyIsDown = true
					mLoopRan = false
				}
				handler.postDelayed(mFlashAdjustRunnable, ViewConfiguration.getLongPressTimeout().toLong())
			}
			ACTION_UP -> {
				synchronized(mLock) {
					mKeyIsDown = false
				}
				if (handler.hasCallbacks(mFlashAdjustRunnable)) {
					handler.removeCallbacks(mFlashAdjustRunnable)
				}
				synchronized(mLock) {
					if (!mLoopRan) {
						adjustFlashStep(event.keyCode)
					}
				}
			}
		}
	}

	private fun adjustFlashStep(keyCode: Int) {
		val step = (if (keyCode == KEYCODE_VOLUME_UP) 1f else -1f) /
			SystemProperties.getInt("ro.config.media_vol_steps") { 10 }

		val newLevel = SystemUtils.getFlashlightLevel(SystemUtils.getFlashStrengthPCT() + step)
		SystemUtils.setFlash(true, newLevel, false)
	}

	private inner class FlashAdjustLongRunnable(val mKeyCode: Int) : Runnable {
		override fun run() {
			synchronized(mLock) {
				mLoopRan = true
			}
			Thread {
				try {
					while (true) {
						adjustFlashStep(mKeyCode)

						//noinspection BusyWait
						Thread.sleep(100)

						synchronized(mLock) {
							if (!mKeyIsDown) break
						}
					}
				} catch (ignored: Throwable) {
				}
			}.start()
		}
	}
}
