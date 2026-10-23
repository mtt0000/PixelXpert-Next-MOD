package sh.anirudhgupta109.pxn.xposed.modpacks.systemui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import io.github.libxposed.api.XposedModuleInterface
import sh.anirudhgupta109.pxn.xposed.XPLauncher
import sh.anirudhgupta109.pxn.xposed.XPrefs.Xprefs
import sh.anirudhgupta109.pxn.xposed.XposedModPack
import sh.anirudhgupta109.pxn.xposed.annotations.SystemUIModPack
import sh.anirudhgupta109.pxn.xposed.utils.SystemUIActions

/**
 * Runs, inside SystemUI, the gesture actions that physical keys are mapped to
 * (android/ScreenOffKeys detects the presses in system server): most of
 * [SystemUIActions] needs SystemUI's services and windows.
 *
 * The receiver is guarded by STATUS_BAR_SERVICE, a signature permission no
 * app can hold, so only system server can trigger actions such as reboot.
 * Only SystemUI's main process registers it, or the screenshot process would
 * run every action a second time.
 */
@SystemUIModPack
class KeyActionReceiver(context: Context) : XposedModPack(context) {

	private var animateTorch = false

	override fun onPreferenceUpdated(vararg key: String?) {
		animateTorch = Xprefs.getBoolean("AnimateFlashlight", false)
	}

	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		if (XPLauncher.processName != mContext.packageName) return

		val receiver = object : BroadcastReceiver() {
			override fun onReceive(context: Context, intent: Intent) {
				val value = intent.getStringExtra(SystemUIActions.EXTRA_KEY_ACTION) ?: return
				SystemUIActions.run(mContext, value, animateTorch)
			}
		}
		mContext.registerReceiver(
			receiver,
			IntentFilter(SystemUIActions.ACTION_RUN_KEY_ACTION),
			"android.permission.STATUS_BAR_SERVICE",
			null,
			Context.RECEIVER_EXPORTED,
		)
	}
}
