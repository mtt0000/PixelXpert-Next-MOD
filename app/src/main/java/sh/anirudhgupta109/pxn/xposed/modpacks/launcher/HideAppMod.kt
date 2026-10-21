package sh.anirudhgupta109.pxn.xposed.modpacks.launcher

import android.app.AlertDialog
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.Toast
import io.github.libxposed.api.XposedModuleInterface
import sh.anirudhgupta109.pxn.R
import sh.anirudhgupta109.pxn.util.HiddenApps
import sh.anirudhgupta109.pxn.xposed.XPrefs.Xprefs
import sh.anirudhgupta109.pxn.xposed.XposedModPack
import sh.anirudhgupta109.pxn.xposed.annotations.LauncherModPack
import sh.anirudhgupta109.pxn.xposed.utils.RecentsTaskTools
import sh.anirudhgupta109.pxn.xposed.utils.TaskButtonsHost
import sh.anirudhgupta109.pxn.xposed.utils.reflection.ReflectedClass
import sh.anirudhgupta109.pxn.xposed.utils.toolkit.Logger

/**
 * "Hide app" for the Recents screen.
 *
 *  - a button on every card adds the app to the hidden list and removes the card
 *    (the task itself is left running);
 *  - hidden apps are filtered out of the data RecentsView receives
 *    (`applyLoadPlan`), so they stay hidden every time Recents opens;
 *  - a "manage" button in the Recents overlay opens a dialog to un-hide apps.
 *
 * The list is the CSV preference [HiddenApps.PREF_KEY], shared with the module UI
 * ("Hidden apps" screen in Custom Settings). Toggle: `RecentHideApp` (default on).
 */
@LauncherModPack
class HideAppMod(context: Context) : XposedModPack(context), TaskButtonsHost.Provider {

	@Volatile
	private var enabled = true

	private var manageButton: ImageButton? = null
	private var overlayVisibility = View.GONE

	override val tag: String = TaskButtonsHost.TAG_PREFIX + "hide"
	override val slot: Int = 1
	override val iconRes: Int = R.drawable.ic_hide_app

	override fun isEnabled(): Boolean = enabled

	private fun hiddenList(): List<String> = HiddenApps.parse(Xprefs.getString(HiddenApps.PREF_KEY, ""))

	private fun saveHidden(list: Collection<String>) {
		Xprefs.edit().putString(HiddenApps.PREF_KEY, HiddenApps.join(list)).apply()
	}

	override fun onPreferenceUpdated(vararg key: String?) {
		enabled = Xprefs.getBoolean("RecentHideApp", true)
		TaskButtonsHost.requestRefresh()
		updateManageButton()
	}

	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		TaskButtonsHost.register(this)

		val recentsViewClass = ReflectedClass.of("com.android.quickstep.views.RecentsView")
		val recentsClazz = recentsViewClass.clazz
		if (recentsClazz != null) {
			// Data level filter: hidden apps never reach RecentsView.
			TaskButtonsHost.hookInHierarchy(recentsClazz, "applyLoadPlan", true) { param ->
				filterLoadPlan(param.args)
			}

			recentsViewClass.after("setVisibility").run { param ->
				overlayVisibility = param.args[0] as Int
				updateManageButton()
			}
		}

		val overviewActionsViewClass = ReflectedClass.ofIfPossible("com.android.quickstep.views.OverviewActionsView")
		if (overviewActionsViewClass.clazz != null) {
			overviewActionsViewClass.before("onFinishInflate").run { param ->
				try {
					addManageButton(param.thisObject as FrameLayout)
				} catch (t: Throwable) {
					Logger.log(RecentsTaskTools.LOG + "manage button failed", t)
				}
			}
		}
	}

	// ----------------------------------------------------------------- filter

	private fun filterLoadPlan(args: Array<Any?>) {
		if (!enabled) return
		val hidden = hiddenList()
		if (hidden.isEmpty()) return

		val index = args.indexOfFirst { it is List<*> }
		if (index < 0) return
		val groups = args[index] as List<*>

		val kept = ArrayList<Any?>(groups.size)
		for (group in groups) {
			val packages = RecentsTaskTools.getTasksOfGroup(group).mapNotNull { RecentsTaskTools.packageOfTask(it) }
			val hideIt = packages.isNotEmpty() && packages.all { hidden.contains(it) }
			if (!hideIt) kept.add(group)
		}
		if (kept.size != groups.size) {
			args[index] = kept
			Logger.log(RecentsTaskTools.LOG + "hidden " + (groups.size - kept.size) + " card(s) from the load plan")
		}
	}

	// ---------------------------------------------------------------- card button

	override fun onClick(taskView: ViewGroup) {
		val packages = RecentsTaskTools.getPackageNames(taskView)
		if (packages.isEmpty()) return

		val list = LinkedHashSet(hiddenList())
		list.addAll(packages)
		saveHidden(list)

		val label = RecentsTaskTools.appLabel(mContext, packages[0])
		Toast.makeText(mContext, RecentsTaskTools.moduleString(R.string.hide_app_toast, label), Toast.LENGTH_SHORT).show()

		RecentsTaskTools.dismissTaskView(taskView, false)
		updateManageButton()
	}

	override fun onRefresh(taskView: ViewGroup) {
		RecentsTaskTools.restoreIfRebound(taskView)
	}

	// -------------------------------------------------------------- manage button

	private fun addManageButton(parent: FrameLayout) {
		val ctx = parent.context
		val button = RecentsTaskTools.createRoundButton(ctx, R.drawable.ic_manage_hidden, TaskButtonsHost.TAG_PREFIX + "manage", 10)
		val size = RecentsTaskTools.dp(ctx, 44f)
		val params = FrameLayout.LayoutParams(size, size)
		params.gravity = Gravity.START or Gravity.CENTER_VERTICAL
		params.leftMargin = RecentsTaskTools.dp(ctx, 16f)
		button.layoutParams = params
		button.elevation = RecentsTaskTools.dp(ctx, 8f).toFloat()
		button.setOnClickListener { showManageDialog(it.context) }
		button.visibility = View.GONE

		parent.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
		parent.addView(button)
		manageButton = button
		updateManageButton()
	}

	private fun updateManageButton() {
		val button = manageButton ?: return
		button.post {
			val show = enabled && hiddenList().isNotEmpty() && overlayVisibility == View.VISIBLE
			button.visibility = if (show) View.VISIBLE else View.GONE
		}
	}

	private fun showManageDialog(context: Context) {
		val hidden = hiddenList()
		if (hidden.isEmpty()) {
			Toast.makeText(context, RecentsTaskTools.moduleString(R.string.hidden_apps_empty), Toast.LENGTH_SHORT).show()
			return
		}
		val labels = hidden.map { RecentsTaskTools.appLabel(context, it) }.toTypedArray<CharSequence>()

		val builder = AlertDialog.Builder(context)
		builder.setTitle(RecentsTaskTools.moduleString(R.string.hidden_apps_dialog_title))
		builder.setItems(labels) { dialog, which ->
			val remaining = hiddenList().filter { it != hidden[which] }
			saveHidden(remaining)
			Toast.makeText(
				context,
				RecentsTaskTools.moduleString(R.string.hidden_apps_unhidden_toast, labels[which]),
				Toast.LENGTH_LONG,
			).show()
			dialog.dismiss()
			updateManageButton()
		}
		builder.setNeutralButton(RecentsTaskTools.moduleString(R.string.hidden_apps_show_all)) { dialog, _ ->
			saveHidden(emptyList())
			Toast.makeText(context, RecentsTaskTools.moduleString(R.string.hidden_apps_all_unhidden_toast), Toast.LENGTH_LONG).show()
			dialog.dismiss()
			updateManageButton()
		}
		builder.setNegativeButton(android.R.string.cancel, null)
		builder.show()
	}
}
