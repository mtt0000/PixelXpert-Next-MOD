package sh.anirudhgupta109.pxn.xposed.modpacks.launcher

import android.app.AlertDialog
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import de.robv.android.xposed.XposedHelpers
import io.github.libxposed.api.XposedModuleInterface
import java.util.concurrent.Executors
import sh.anirudhgupta109.pxn.R
import sh.anirudhgupta109.pxn.util.HiddenApps
import sh.anirudhgupta109.pxn.xposed.XPrefs.Xprefs
import sh.anirudhgupta109.pxn.xposed.XposedModPack
import sh.anirudhgupta109.pxn.xposed.annotations.LauncherModPack
import sh.anirudhgupta109.pxn.xposed.utils.RecentsTaskTools
import sh.anirudhgupta109.pxn.xposed.utils.SystemUtils
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
 *
 * A card is hidden only when every package it holds is hidden, so hiding one
 * half of a split pair never takes an unrelated app with it. The task the user
 * is currently in is never filtered: Recents expects a card for it while the
 * swipe-up gesture is running.
 */
@LauncherModPack
class HideAppMod(context: Context) : XposedModPack(context), TaskButtonsHost.Provider {

	@Volatile
	private var enabled = true

	/**
	 * Local copy of the list. Every Xprefs read is a provider round trip, and
	 * applyLoadPlan runs on the UI thread, so reads come from here and the
	 * copy is refreshed from [onPreferenceUpdated].
	 */
	@Volatile
	private var hidden: List<String> = emptyList()

	@Volatile
	private var manageInActionBar = false

	private var manageButton: ImageButton? = null
	private var overlayVisibility = View.GONE

	/**
	 * Tasks kept out of Recents (task id to package), from the last load plan
	 * and from cards hidden since. Clear-all closes these too, since their
	 * cards are not there for the launcher to dismiss.
	 */
	@Volatile
	private var hiddenTasks: Map<Int, String> = emptyMap()

	/** True when the last load plan was left with no card after filtering. */
	@Volatile
	private var lastPlanEmpty = false

	/**
	 * Manage button shown under Recents' "no recent items" message: with every
	 * open app hidden there are no cards, and the launcher hides the whole
	 * actions bar, [manageButton] included.
	 */
	private var emptyStateButton: ImageButton? = null
	private var emptyStateQueued = false
	private var recentsRef: java.lang.ref.WeakReference<ViewGroup>? = null

	override val tag: String = TaskButtonsHost.TAG_PREFIX + "hide"
	override val slot: Int = 1
	override val iconRes: Int = R.drawable.ic_hide_app

	override fun isEnabled(): Boolean = enabled

	private fun saveHidden(list: Collection<String>) {
		val value = HiddenApps.join(list)
		hidden = HiddenApps.parse(value)
		prefWriter.execute {
			try {
				Xprefs.edit().putString(HiddenApps.PREF_KEY, value).apply()
			} catch (t: Throwable) {
				Logger.log(RecentsTaskTools.LOG + "saving the hidden list failed", t)
			}
		}
		updateManageButton()
	}

	override fun onPreferenceUpdated(vararg key: String?) {
		// The placement is decided when the overlay is inflated, so a change
		// needs a fresh launcher process, like ClearAllButtonMod's switches.
		if (key.isNotEmpty() && key[0] == "RecentHiddenAppsInActionBar") {
			SystemUtils.killSelf()
		}

		enabled = Xprefs.getBoolean("RecentHideApp", true)
		manageInActionBar = Xprefs.getBoolean("RecentHiddenAppsInActionBar", false)
		if (key.isEmpty() || key[0] == HiddenApps.PREF_KEY) {
			hidden = HiddenApps.parse(Xprefs.getString(HiddenApps.PREF_KEY, ""))
		}
		TaskButtonsHost.requestRefresh()
		updateManageButton()
	}

	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		TaskButtonsHost.register(this)

		val recentsViewClass = ReflectedClass.ofIfPossible("com.android.quickstep.views.RecentsView")
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

			// Clear all (the launcher's own button and ClearAllButtonMod's both
			// end up here) also closes the hidden apps.
			TaskButtonsHost.hookInHierarchy(recentsClazz, "dismissAllTasks", false) { _ ->
				closeHiddenTasks()
			}

			recentsViewClass.afterConstruction().run { param ->
				val recents = param.thisObject as? ViewGroup ?: return@run
				recentsRef = java.lang.ref.WeakReference(recents)
				recents.viewTreeObserver.addOnPreDrawListener {
					try {
						syncEmptyStateButton(recents)
					} catch (t: Throwable) {
						Logger.log(RecentsTaskTools.LOG + "empty state button failed", t)
					}
					true
				}
			}
		}

		val overviewActionsViewClass = ReflectedClass.ofIfPossible("com.android.quickstep.views.OverviewActionsView")
		if (overviewActionsViewClass.clazz != null) {
			overviewActionsViewClass.after("onFinishInflate").run { param ->
				try {
					addManageButton(param.thisObject as FrameLayout)
				} catch (t: Throwable) {
					Logger.log(RecentsTaskTools.LOG + "manage button failed", t)
				}
			}
		} else {
			Logger.log(RecentsTaskTools.LOG + "OverviewActionsView not found, no manage button")
		}
	}

	// ----------------------------------------------------------------- filter

	private fun filterLoadPlan(args: Array<Any?>) {
		if (!enabled) return
		val hiddenNow = hidden
		if (hiddenNow.isEmpty()) return

		val index = args.indexOfFirst { it is List<*> }
		if (index < 0) return
		val groups = args[index] as List<*>
		val runningTaskId = runningTaskId()

		val kept = ArrayList<Any?>(groups.size)
		val removedTasks = LinkedHashMap<Int, String>()
		for (group in groups) {
			val tasks = RecentsTaskTools.getTasksOfGroup(group)
			val packages = tasks.mapNotNull { RecentsTaskTools.packageOfTask(it) }
			val isRunning = runningTaskId != null && tasks.any { RecentsTaskTools.idOfTask(it) == runningTaskId }
			val hideIt = !isRunning && packages.isNotEmpty() && packages.all { hiddenNow.contains(it) }
			if (hideIt) {
				for (task in tasks) {
					val id = RecentsTaskTools.idOfTask(task) ?: continue
					val pkg = RecentsTaskTools.packageOfTask(task) ?: continue
					removedTasks[id] = pkg
				}
			} else {
				kept.add(group)
			}
		}
		hiddenTasks = removedTasks
		lastPlanEmpty = kept.isEmpty()
		if (kept.size != groups.size) {
			args[index] = kept
			Logger.log(RecentsTaskTools.LOG + "hidden " + (groups.size - kept.size) + " card(s) from the load plan")
		}
	}

	/** Id of the task in the foreground, via the launcher's own ActivityManagerWrapper. */
	private fun runningTaskId(): Int? = try {
		val wrapperClass = XposedHelpers.findClassIfExists(
			"com.android.systemui.shared.system.ActivityManagerWrapper",
			mContext.classLoader,
		)
		val wrapper = XposedHelpers.callStaticMethod(wrapperClass, "getInstance")
		val info = XposedHelpers.callMethod(wrapper, "getRunningTask")
		if (info == null) null else XposedHelpers.getIntField(info, "taskId")
	} catch (ignored: Throwable) {
		null
	}

	/**
	 * Removes the hidden apps' tasks from the system's recents and drops those
	 * apps from the hidden list: once closed they have nothing left to hide.
	 * Apps hidden ahead of time from the settings screen, with no task open,
	 * stay on the list.
	 */
	private fun closeHiddenTasks() {
		if (!enabled) return
		val tasks = hiddenTasks
		if (tasks.isEmpty()) return
		hiddenTasks = emptyMap()

		val loader = mContext.classLoader
		prefWriter.execute {
			for (id in tasks.keys) RecentsTaskTools.removeTaskById(loader, id)
		}

		val closed = tasks.values.toSet()
		saveHidden(hidden.filterNot { it in closed })
		lastPlanEmpty = false
		Logger.log(RecentsTaskTools.LOG + "clear all closed " + tasks.size + " hidden task(s): " + closed.joinToString())
	}

	// ---------------------------------------------------------------- card button

	override fun onClick(taskView: ViewGroup) {
		try {
			val packages = RecentsTaskTools.getPackageNames(taskView)
			if (packages.isEmpty()) return

			val list = LinkedHashSet(hidden)
			list.addAll(packages)
			saveHidden(list)

			// The card goes without its task, so clear-all has to know about it
			// before Recents next reloads.
			val tasks = LinkedHashMap(hiddenTasks)
			for (task in RecentsTaskTools.getTasks(taskView)) {
				val id = RecentsTaskTools.idOfTask(task) ?: continue
				val pkg = RecentsTaskTools.packageOfTask(task) ?: continue
				tasks[id] = pkg
			}
			hiddenTasks = tasks

			val label = packages.joinToString { RecentsTaskTools.appLabel(mContext, it) }
			RecentsTaskTools.toast(mContext, RecentsTaskTools.moduleString(R.string.hide_app_toast, label))

			RecentsTaskTools.dismissTaskView(taskView, false)
		} catch (t: Throwable) {
			Logger.log(RecentsTaskTools.LOG + "hide failed", t)
		}
	}

	override fun onRefresh(taskView: ViewGroup) {
		RecentsTaskTools.restoreIfRebound(taskView)
	}

	// -------------------------------------------------------------- manage button

	/**
	 * Adds the "manage hidden apps" button to the Recents overlay, in one of
	 * two places chosen by `RecentHiddenAppsInActionBar`:
	 *  - on: in the Screenshot / Select row, right after the clear-all button
	 *    when that one is there too (the old PixelXpert placement);
	 *  - off (default): at the end edge. When ClearAllButtonMod has stretched
	 *    the bar over the whole screen, the button sits right above its
	 *    floating clear-all button, clear of the cards; otherwise at the end
	 *    of the bar itself.
	 */
	private fun addManageButton(parent: FrameLayout) {
		val ctx = parent.context
		val button = RecentsTaskTools.createRoundButton(ctx, R.drawable.ic_manage_hidden, TaskButtonsHost.TAG_PREFIX + "manage", 10)
		val size = RecentsTaskTools.dp(ctx, 44f)
		button.elevation = RecentsTaskTools.dp(ctx, 8f).toFloat()
		button.contentDescription = RecentsTaskTools.moduleString(R.string.hidden_apps_title)
		button.setOnClickListener { showManageDialog(it.context) }
		button.visibility = View.GONE

		val row = if (manageInActionBar) RecentsTaskTools.findActionButtonsRow(parent) else null
		if (row != null) {
			val params = LinearLayout.LayoutParams(size, size)
			params.gravity = Gravity.CENTER_VERTICAL
			params.marginStart = RecentsTaskTools.dp(ctx, 8f)
			params.marginEnd = RecentsTaskTools.dp(ctx, 8f)
			val clearAll = row.findViewWithTag<View>(RecentsTaskTools.CLEAR_ALL_TAG)
			val index = if (clearAll != null) row.indexOfChild(clearAll) + 1 else 0
			row.addView(button, index, params)
		} else {
			val params = FrameLayout.LayoutParams(size, size)
			params.gravity = Gravity.END or Gravity.CENTER_VERTICAL
			params.marginEnd = RecentsTaskTools.dp(ctx, 16f)
			if (parent.layoutParams?.height == ViewGroup.LayoutParams.MATCH_PARENT) {
				// Centred like clear-all, then lifted by half of each button
				// (clear-all is at most 48dp) plus a 12dp gap.
				params.bottomMargin = RecentsTaskTools.dp(ctx, 24f + 12f + 22f)
			}
			parent.addView(button, params)
		}

		TaskButtonsHost.registerExtraButton(button)
		manageButton = button
		updateManageButton()
	}

	/**
	 * Shows [emptyStateButton] while Recents is on screen with no card left
	 * because every open app is hidden. Runs before each frame Recents draws,
	 * so it only touches the button when its state actually changes.
	 */
	private fun syncEmptyStateButton(recents: ViewGroup) {
		// lastPlanEmpty keeps the button away while a non-empty plan is still
		// turning into cards; hiddenTasks covers the last card hidden by hand,
		// which empties Recents without a reload.
		val show = enabled &&
			hidden.isNotEmpty() &&
			(lastPlanEmpty || hiddenTasks.isNotEmpty()) &&
			recents.isShown &&
			RecentsTaskTools.collectTaskViews(recents).isEmpty()
		// Recents fades its content in and out (and may keep it at 0 behind the
		// home screen), so the button follows that opacity.
		val alpha = if (show) recentsAlpha(recents) else 0f

		val button = emptyStateButton
		if (button == null) {
			if (show && !emptyStateQueued) {
				// Not from inside the pre-draw pass: adding a view there would
				// change the tree mid-traversal.
				emptyStateQueued = true
				recents.post { addEmptyStateButton(recents) }
			}
			return
		}

		val target = if (alpha > 0.05f) View.VISIBLE else View.GONE
		if (target == View.VISIBLE) {
			// The navigation bar can change (gestures / 3 buttons, rotation).
			val params = button.layoutParams as? FrameLayout.LayoutParams
			val margin = emptyStateBottomMargin(button.parent as? View ?: recents)
			if (params != null && params.bottomMargin != margin) {
				params.bottomMargin = margin
				button.layoutParams = params
			}
		}
		if (button.visibility != target) button.visibility = target
		if (target == View.VISIBLE && kotlin.math.abs(button.alpha - alpha) > 0.01f) button.alpha = alpha
	}

	/** Navigation bar height plus a 32dp gap. */
	private fun emptyStateBottomMargin(view: View): Int {
		val navBar = try {
			view.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.navigationBars())?.bottom ?: 0
		} catch (ignored: Throwable) {
			0
		}
		return navBar + RecentsTaskTools.dp(view.context, 32f)
	}

	/** Opacity of Recents' content: its own alpha (and its parents') times its content alpha. */
	private fun recentsAlpha(recents: ViewGroup): Float {
		var alpha = 1f
		var current: View? = recents
		while (current != null) {
			alpha *= current.alpha
			current = current.parent as? View
		}
		return alpha * contentAlpha(recents)
	}

	/** How this build exposes the content alpha: 0 unknown, 1 getter, 2 field, 3 neither. */
	private var contentAlphaSource = 0

	private fun contentAlpha(recents: ViewGroup): Float {
		if (contentAlphaSource == 0 || contentAlphaSource == 1) {
			try {
				val value = (XposedHelpers.callMethod(recents, "getContentAlpha") as Number).toFloat()
				contentAlphaSource = 1
				return value
			} catch (ignored: Throwable) {
				if (contentAlphaSource == 1) return 1f
			}
		}
		if (contentAlphaSource == 0 || contentAlphaSource == 2) {
			try {
				val value = XposedHelpers.getFloatField(recents, "mContentAlpha")
				contentAlphaSource = 2
				return value
			} catch (ignored: Throwable) {
				if (contentAlphaSource == 2) return 1f
			}
		}
		if (contentAlphaSource != 3) {
			contentAlphaSource = 3
			Logger.log(RecentsTaskTools.LOG + "RecentsView content alpha not readable")
		}
		return 1f
	}


	/**
	 * The empty-state button goes into Recents' parent (the launcher's drag
	 * layer), at the bottom centre: where the actions bar sits when there are
	 * cards, clear of the navigation bar.
	 */
	private fun addEmptyStateButton(recents: ViewGroup) {
		if (emptyStateButton != null) return
		val parent = recents.parent as? FrameLayout
		if (parent == null) {
			Logger.log(RecentsTaskTools.LOG + "Recents parent is not a FrameLayout, no empty state button")
			return
		}

		val ctx = recents.context
		val button = RecentsTaskTools.createRoundButton(ctx, R.drawable.ic_manage_hidden, TaskButtonsHost.TAG_PREFIX + "manage_empty", 14)
		val size = RecentsTaskTools.dp(ctx, 56f)
		val params = FrameLayout.LayoutParams(size, size)
		params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
		params.bottomMargin = emptyStateBottomMargin(parent)
		button.elevation = RecentsTaskTools.dp(ctx, 8f).toFloat()
		button.contentDescription = RecentsTaskTools.moduleString(R.string.hidden_apps_title)
		button.setOnClickListener { showManageDialog(it.context) }
		button.visibility = View.GONE

		parent.addView(button, params)
		TaskButtonsHost.registerExtraButton(button)
		emptyStateButton = button
		syncEmptyStateButton(recents)
	}

	private fun updateManageButton() {
		recentsRef?.get()?.let { recents -> recents.post { syncEmptyStateButton(recents) } }
		val button = manageButton ?: return
		button.post {
			val show = enabled && hidden.isNotEmpty() && overlayVisibility == View.VISIBLE
			val target = if (show) View.VISIBLE else View.GONE
			if (button.visibility != target) button.visibility = target
		}
	}

	private fun showManageDialog(context: Context) {
		try {
			val hiddenNow = hidden
			if (hiddenNow.isEmpty()) {
				RecentsTaskTools.toast(mContext, RecentsTaskTools.moduleString(R.string.hidden_apps_empty))
				return
			}
			val labels = hiddenNow.map { RecentsTaskTools.appLabel(context, it) }.toTypedArray<CharSequence>()

			val theme = if (SystemUtils.isDarkMode()) {
				android.R.style.Theme_DeviceDefault_Dialog_Alert
			} else {
				android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
			}

			// setItems wires the list itself; the old build reached for
			// getListView() before show(), which throws.
			AlertDialog.Builder(context, theme)
				.setTitle(RecentsTaskTools.moduleString(R.string.hidden_apps_dialog_title))
				.setItems(labels) { dialog, which ->
					saveHidden(hidden.filter { it != hiddenNow[which] })
					RecentsTaskTools.toast(
						mContext,
						RecentsTaskTools.moduleString(R.string.hidden_apps_unhidden_toast, labels[which]),
					)
					dialog.dismiss()
				}
				.setNeutralButton(RecentsTaskTools.moduleString(R.string.hidden_apps_show_all)) { dialog, _ ->
					saveHidden(emptyList())
					RecentsTaskTools.toast(mContext, RecentsTaskTools.moduleString(R.string.hidden_apps_all_unhidden_toast))
					dialog.dismiss()
				}
				.setNegativeButton(android.R.string.cancel, null)
				.show()
		} catch (t: Throwable) {
			Logger.log(RecentsTaskTools.LOG + "manage dialog failed", t)
		}
	}

	companion object {
		/** One background thread for the provider writes, so they keep their order. */
		private val prefWriter = Executors.newSingleThreadExecutor()
	}
}
