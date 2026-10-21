package sh.anirudhgupta109.pxn.xposed.modpacks.launcher

import android.content.Context
import android.view.ViewGroup
import io.github.libxposed.api.XposedModuleInterface
import sh.anirudhgupta109.pxn.R
import sh.anirudhgupta109.pxn.xposed.XPrefs.Xprefs
import sh.anirudhgupta109.pxn.xposed.XposedModPack
import sh.anirudhgupta109.pxn.xposed.annotations.LauncherModPack
import sh.anirudhgupta109.pxn.xposed.utils.RecentsTaskTools
import sh.anirudhgupta109.pxn.xposed.utils.TaskButtonsHost

/**
 * Adds a "kill app" button to every card of the Recents screen.
 *
 * Tapping it force-stops the app(s) of the card and then removes the card
 * exactly like a swipe-up does (the task is removed from the system's recents too).
 * Toggle: preference `RecentKillApp` (default on).
 */
@LauncherModPack
class KillAppMod(context: Context) : XposedModPack(context), TaskButtonsHost.Provider {

	@Volatile
	private var enabled = true

	override val tag: String = TaskButtonsHost.TAG_PREFIX + "kill"
	override val slot: Int = 0
	override val iconRes: Int = R.drawable.ic_kill_app

	override fun isEnabled(): Boolean = enabled

	override fun onPreferenceUpdated(vararg key: String?) {
		enabled = Xprefs.getBoolean("RecentKillApp", true)
		TaskButtonsHost.requestRefresh()
	}

	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		TaskButtonsHost.register(this)
	}

	override fun onClick(taskView: ViewGroup) {
		val packages = RecentsTaskTools.getPackageNames(taskView)
		for (pkg in packages) RecentsTaskTools.forceStop(mContext, pkg)
		RecentsTaskTools.dismissTaskView(taskView, true)
	}

	override fun onRefresh(taskView: ViewGroup) {
		RecentsTaskTools.restoreIfRebound(taskView)
	}
}
