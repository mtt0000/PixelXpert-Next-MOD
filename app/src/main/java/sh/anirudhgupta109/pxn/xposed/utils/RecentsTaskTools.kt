package sh.anirudhgupta109.pxn.xposed.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import androidx.core.content.res.ResourcesCompat
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method
import java.util.WeakHashMap
import sh.anirudhgupta109.pxn.Constants
import sh.anirudhgupta109.pxn.xposed.XPLauncher
import sh.anirudhgupta109.pxn.xposed.utils.toolkit.Logger

/**
 * Helpers shared by KillAppMod and HideAppMod (Pixel Launcher process).
 *
 * Everything that touches launcher internals is reflective and tolerant: each
 * strategy is tried in turn and a failure only logs a line prefixed with
 * [LOG], so a future launcher update degrades a feature instead of crashing
 * the launcher. Grep the LSPosed log for "PXN-Recents" when debugging.
 */
object RecentsTaskTools {

	const val LOG: String = "PXN-Recents: "

	private val awayViews = WeakHashMap<View, List<String>>()

	// ------------------------------------------------------------------ misc

	@JvmStatic
	fun dp(context: Context, value: Float): Int =
		TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).toInt()

	/** String from the MODULE resources (localised), formatted with [args]. */
	@JvmStatic
	fun moduleString(resId: Int, vararg args: Any?): String {
		val res = XPLauncher.moduleResources ?: return ""
		return try {
			if (args.isEmpty()) res.getString(resId) else res.getString(resId, *args)
		} catch (ignored: Throwable) {
			""
		}
	}

	@JvmStatic
	fun appLabel(context: Context, packageName: String): String = try {
		val pm = context.packageManager
		pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
	} catch (ignored: Throwable) {
		packageName
	}

	private inline fun <T> attempt(block: () -> T?): T? = try {
		block()
	} catch (ignored: Throwable) {
		null
	}

	// ------------------------------------------------------- view detection

	private fun classChainHasSimpleName(obj: Any?, simpleName: String): Boolean {
		var c: Class<*>? = obj?.javaClass
		while (c != null && c != Any::class.java) {
			if (c.simpleName == simpleName) return true
			c = c.superclass
		}
		return false
	}

	@JvmStatic
	fun isTaskView(obj: Any?): Boolean = classChainHasSimpleName(obj, "TaskView")

	@JvmStatic
	fun isRecentsView(obj: Any?): Boolean = classChainHasSimpleName(obj, "RecentsView")

	/** Every TaskView below [root] (GONE ones included), looking at most 3 levels deep. */
	@JvmStatic
	fun collectTaskViews(
		root: ViewGroup,
		depth: Int = 0,
		out: MutableList<ViewGroup> = ArrayList(),
	): MutableList<ViewGroup> {
		for (i in 0 until root.childCount) {
			val child = root.getChildAt(i)
			if (child is ViewGroup) {
				if (isTaskView(child)) {
					out.add(child)
				} else if (depth < 2) {
					collectTaskViews(child, depth + 1, out)
				}
			}
		}
		return out
	}

	@JvmStatic
	fun findRecentsView(taskView: View): ViewGroup? {
		var p: Any? = taskView.parent
		while (p != null) {
			if (p is ViewGroup && isRecentsView(p)) return p
			p = (p as? View)?.parent
		}
		return null
	}

	// -------------------------------------------------------- task extraction

	private fun taskFromContainer(container: Any): Any? {
		for (field in arrayOf("task", "mTask")) {
			val t = attempt { XposedHelpers.getObjectField(container, field) }
			if (t != null) return t
		}
		val viaGetter = attempt { XposedHelpers.callMethod(container, "getTask") }
		if (viaGetter != null) return viaGetter
		// The object may already be a Task itself.
		return if (attempt { XposedHelpers.getObjectField(container, "key") } != null) container else null
	}

	private fun scanFields(target: Any, consume: (Any?) -> Unit) {
		var clazz: Class<*>? = target.javaClass
		while (clazz != null && clazz != Any::class.java && !clazz.name.startsWith("android.")) {
			for (f in clazz.declaredFields) {
				try {
					f.isAccessible = true
					val v = f.get(target) ?: continue
					if (v is Iterable<*> || v is Array<*>) {
						consume(v)
					} else if (v.javaClass.name.contains("Task")) {
						consume(listOf(v))
					}
				} catch (ignored: Throwable) {
				}
			}
			clazz = clazz.superclass
		}
	}

	/** Task objects held by a TaskView (several when it is a split-screen group). */
	@JvmStatic
	fun getTasks(taskView: Any?): List<Any> {
		if (taskView == null) return emptyList()
		val tasks = ArrayList<Any>()

		fun addTask(t: Any?) {
			if (t != null && !tasks.contains(t)) tasks.add(t)
		}

		fun addContainers(src: Any?) {
			val items: Iterable<*> = when (src) {
				is Array<*> -> src.asIterable()
				is Iterable<*> -> src
				else -> return
			}
			for (c in items) {
				if (c != null) addTask(taskFromContainer(c))
			}
		}

		fun addTaskCollection(src: Any?) {
			val items: Iterable<*> = when (src) {
				is Array<*> -> src.asIterable()
				is Iterable<*> -> src
				else -> return
			}
			for (t in items) addTask(t)
		}

		attempt { addContainers(XposedHelpers.callMethod(taskView, "getTaskContainers")) }
		if (tasks.isEmpty()) attempt { addContainers(XposedHelpers.getObjectField(taskView, "mTaskContainers")) }
		if (tasks.isEmpty()) attempt { addContainers(XposedHelpers.callMethod(taskView, "getTaskIdAttributeContainers")) }
		if (tasks.isEmpty()) attempt { addContainers(XposedHelpers.getObjectField(taskView, "mTaskIdAttributeContainer")) }
		if (tasks.isEmpty()) attempt { addTaskCollection(XposedHelpers.callMethod(taskView, "getTasks")) }
		if (tasks.isEmpty()) attempt { addTask(XposedHelpers.callMethod(taskView, "getFirstTask")) }
		if (tasks.isEmpty()) attempt { addTask(XposedHelpers.callMethod(taskView, "getTask")) }
		if (tasks.isEmpty()) attempt { addTask(XposedHelpers.getObjectField(taskView, "mTask")) }
		if (tasks.isEmpty()) attempt { scanFields(taskView) { addContainers(it) } }

		if (tasks.isEmpty()) {
			Logger.log(LOG + "no Task found in " + taskView.javaClass.name)
		}
		return tasks
	}

	/** Task objects held by a GroupTask (the element type of RecentsView.applyLoadPlan's list). */
	@JvmStatic
	fun getTasksOfGroup(group: Any?): List<Any> {
		if (group == null) return emptyList()
		val out = ArrayList<Any>()

		fun addAll(src: Any?) {
			val items: Iterable<*> = when (src) {
				is Array<*> -> src.asIterable()
				is Iterable<*> -> src
				else -> return
			}
			for (t in items) {
				if (t != null && !out.contains(t)) out.add(t)
			}
		}

		attempt { addAll(XposedHelpers.callMethod(group, "getTasks")) }
		if (out.isEmpty()) {
			for (f in arrayOf("tasks", "mTasks")) {
				attempt { addAll(XposedHelpers.getObjectField(group, f)) }
			}
		}
		if (out.isEmpty()) {
			for (f in arrayOf("task1", "task2")) {
				val t = attempt { XposedHelpers.getObjectField(group, f) }
				if (t != null && !out.contains(t)) out.add(t)
			}
		}
		if (out.isEmpty()) {
			for (m in arrayOf("getTask1", "getTask2")) {
				val t = attempt { XposedHelpers.callMethod(group, m) }
				if (t != null && !out.contains(t)) out.add(t)
			}
		}
		return out
	}

	@JvmStatic
	fun packageOfTask(task: Any): String? {
		var key: Any? = attempt { XposedHelpers.getObjectField(task, "key") }
		if (key == null) key = attempt { XposedHelpers.callMethod(task, "getKey") }

		if (key != null) {
			val viaName = attempt { XposedHelpers.callMethod(key, "getPackageName") as? String }
			if (!viaName.isNullOrEmpty()) return viaName

			val viaComponent = attempt { (XposedHelpers.callMethod(key, "getComponent") as? ComponentName)?.packageName }
			if (!viaComponent.isNullOrEmpty()) return viaComponent

			val intent = attempt { XposedHelpers.getObjectField(key, "baseIntent") as? Intent }
			val viaIntent = intent?.component?.packageName ?: intent?.getPackage()
			if (!viaIntent.isNullOrEmpty()) return viaIntent
		}

		val top = attempt { (XposedHelpers.getObjectField(task, "topActivity") as? ComponentName)?.packageName }
		return if (!top.isNullOrEmpty()) top else null
	}

	@JvmStatic
	fun idOfTask(task: Any): Int? {
		var key: Any? = attempt { XposedHelpers.getObjectField(task, "key") }
		if (key == null) key = attempt { XposedHelpers.callMethod(task, "getKey") }
		if (key != null) {
			val viaField = attempt { XposedHelpers.getIntField(key, "id") }
			if (viaField != null) return viaField
			val viaGetter = attempt { XposedHelpers.callMethod(key, "getId") as? Int }
			if (viaGetter != null) return viaGetter
		}
		return attempt { XposedHelpers.getIntField(task, "id") }
	}

	@JvmStatic
	fun getPackageNames(taskView: Any?): List<String> =
		getTasks(taskView).mapNotNull { packageOfTask(it) }.distinct()

	@JvmStatic
	fun getTaskIds(taskView: Any?): List<Int> =
		getTasks(taskView).mapNotNull { idOfTask(it) }.distinct()

	// ------------------------------------------------------------ force stop

	/**
	 * Force-stops [packageName] without blocking the UI thread.
	 *
	 * Tier 1: ActivityManager.forceStopPackage from the launcher process; the
	 * Framework mod `PackageManager` already grants FORCE_STOP_PACKAGES to the
	 * launcher through its AMS.checkCallingPermission hook.
	 * Tier 2: `am force-stop` through the module's root proxy
	 * (the launcher is listed in R.array.root_requirement).
	 */
	@JvmStatic
	fun forceStop(context: Context, packageName: String) {
		if (packageName == Constants.LAUNCHER_PACKAGE ||
			packageName == Constants.SYSTEM_UI_PACKAGE ||
			packageName == Constants.SYSTEM_FRAMEWORK_PACKAGE
		) {
			return
		}
		if (!SAFE_PACKAGE.matches(packageName)) return

		Thread {
			var done = false
			try {
				val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
				val method = am.javaClass.getMethod("forceStopPackage", String::class.java)
				method.invoke(am, packageName)
				done = true
			} catch (t: Throwable) {
				Logger.log(LOG + "forceStopPackage($packageName) failed: " + (t.cause ?: t))
			}

			if (!done) {
				XPLauncher.enqueueProxyCommand { proxy ->
					proxy.runRootCommand("am force-stop $packageName")
				}
			}
		}.start()
	}

	private val SAFE_PACKAGE = Regex("^[A-Za-z0-9_.]+$")

	// -------------------------------------------------------------- dismissal

	private fun buildDismissArgs(
		method: Method,
		taskView: View,
		taskId: Int?,
		removeTask: Boolean,
	): Array<Any?>? {
		val types = method.parameterTypes
		if (types.isEmpty() || types.size > 3) return null

		val args = arrayOfNulls<Any?>(types.size)
		var booleans = 0
		for ((i, t) in types.withIndex()) {
			if (i == 0 && t.isAssignableFrom(taskView.javaClass)) {
				args[i] = taskView
			} else if (i == 0 && t == Int::class.javaPrimitiveType) {
				args[i] = taskId ?: return null
			} else if (t == Boolean::class.javaPrimitiveType) {
				// first boolean = animate, second boolean = removeTask
				args[i] = if (booleans == 0) true else removeTask
				booleans++
			} else {
				return null
			}
		}
		// A lone boolean cannot be told apart (animate? remove?): only trust it when
		// removal is what we want anyway.
		if (!removeTask && booleans < 2) return null
		return args
	}

	private fun removeTaskById(loader: ClassLoader?, taskId: Int) {
		val names = arrayOf(
			"com.android.systemui.shared.system.ActivityManagerWrapper",
			"com.android.systemui.shared.recents.system.ActivityManagerWrapper",
		)
		for (name in names) {
			try {
				val cls = XposedHelpers.findClassIfExists(name, loader) ?: continue
				val wrapper = XposedHelpers.callStaticMethod(cls, "getInstance")
				XposedHelpers.callMethod(wrapper, "removeTask", taskId)
				return
			} catch (t: Throwable) {
				Logger.log(LOG + "removeTask via $name failed: " + (t.cause ?: t))
			}
		}
	}

	/** Last resort when the launcher offers no dismiss method we can call. */
	private fun animateAway(taskView: ViewGroup) {
		try {
			awayViews[taskView] = getPackageNames(taskView)
			taskView.animate().cancel()
			taskView.animate()
				.alpha(0f)
				.setDuration(180L)
				.withEndAction { taskView.visibility = View.GONE }
				.start()
		} catch (t: Throwable) {
			taskView.visibility = View.GONE
		}
	}

	/** Undoes [animateAway] when the launcher recycled the view for another task. */
	@JvmStatic
	fun restoreIfRebound(taskView: View) {
		val recorded = awayViews[taskView] ?: return
		if (getPackageNames(taskView) != recorded) {
			awayViews.remove(taskView)
			taskView.animate().cancel()
			taskView.alpha = 1f
			taskView.visibility = View.VISIBLE
		}
	}

	/**
	 * Removes a card from Recents the way a swipe-up does.
	 *
	 * [removeTask] true  = swipe-up semantics (the task is also removed from the system's recents)
	 * [removeTask] false = only the card disappears, the task stays in the system's recents
	 *
	 * @return true when a launcher dismiss method was found and invoked.
	 */
	@JvmStatic
	fun dismissTaskView(taskView: ViewGroup, removeTask: Boolean): Boolean {
		val recents = findRecentsView(taskView)
		val ids = getTaskIds(taskView)
		var dismissed = false

		if (recents != null) {
			val candidates = ArrayList<Method>()
			var c: Class<*>? = recents.javaClass
			while (c != null && c != Any::class.java && !c.name.startsWith("android.")) {
				for (m in c.declaredMethods) {
					if (m.name == "dismissTaskView" || m.name == "dismissTask") candidates.add(m)
				}
				c = c.superclass
			}
			candidates.sortByDescending { it.parameterCount }

			for (m in candidates) {
				val args = buildDismissArgs(m, taskView, ids.firstOrNull(), removeTask) ?: continue
				try {
					m.isAccessible = true
					m.invoke(recents, *args)
					Logger.log(
						LOG + "dismissed via " + m.declaringClass.simpleName + "." + m.name +
							"(" + m.parameterTypes.joinToString { it.simpleName } + ")",
					)
					dismissed = true
					break
				} catch (t: Throwable) {
					Logger.log(LOG + "dismiss via " + m.name + " failed: " + (t.cause ?: t))
				}
			}
		}

		if (!dismissed) {
			if (removeTask) {
				val loader = (recents ?: taskView).javaClass.classLoader
				for (id in ids) removeTaskById(loader, id)
			}
			animateAway(taskView)
		}
		return dismissed
	}

	// ----------------------------------------------------------------- buttons

	/** Round, semi-transparent black button with a white icon taken from the MODULE resources. */
	@JvmStatic
	fun createRoundButton(context: Context, iconRes: Int, tag: String, paddingDp: Int): ImageButton {
		val button = ImageButton(context, null, android.R.attr.borderlessButtonStyle)
		button.tag = tag

		try {
			button.setImageDrawable(ResourcesCompat.getDrawable(XPLauncher.moduleResources!!, iconRes, context.theme))
		} catch (t: Throwable) {
			button.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
		}
		button.setColorFilter(Color.WHITE)

		val background = GradientDrawable()
		background.setColor(Color.argb(0x99, 0, 0, 0))
		background.shape = GradientDrawable.OVAL
		button.background = background

		val padding = dp(context, paddingDp.toFloat())
		button.setPadding(padding, padding, padding, padding)
		button.isClickable = true
		button.isFocusable = true
		return button
	}
}
