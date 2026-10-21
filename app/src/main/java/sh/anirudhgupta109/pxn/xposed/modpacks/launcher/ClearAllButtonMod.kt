package sh.anirudhgupta109.pxn.xposed.modpacks.launcher

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.res.ResourcesCompat
import de.robv.android.xposed.XposedHelpers.findMethodBestMatch
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Method
import sh.anirudhgupta109.pxn.R
import sh.anirudhgupta109.pxn.xposed.XPLauncher
import sh.anirudhgupta109.pxn.xposed.XPrefs.Xprefs
import sh.anirudhgupta109.pxn.xposed.XposedModPack
import sh.anirudhgupta109.pxn.xposed.annotations.LauncherModPack
import sh.anirudhgupta109.pxn.xposed.utils.SystemUtils
import sh.anirudhgupta109.pxn.xposed.utils.reflection.ReflectedClass

/**
 * Adds a floating "clear all" button to the Recents screen.
 *
 * Two looks, chosen by the `RecentClearAllLegacyStyle` preference:
 *  - off (default): the original Next-MOD look, a bare tinted icon;
 *  - on: the round look of the old PixelXpert build, a 44dp circle with
 *    a semi-transparent black fill and a white icon.
 *
 * The button is only added when `RecentClearAllReposition` is on. Flipping either
 * preference kills the launcher process so the hooks re-register in the new configuration.
 */
@LauncherModPack
class ClearAllButtonMod(context: Context) : XposedModPack(context) {
	private var recentView: Any? = null
	private var clearAllIcon: ImageView? = null
	private var clearAllButton: FrameLayout? = null

	override fun onPreferenceUpdated(vararg key: String?) {
		if (key.isNotEmpty() && (key[0] == "RecentClearAllReposition" || key[0] == "RecentClearAllLegacyStyle")) {
			SystemUtils.killSelf()
		}

		RecentClearAllReposition = Xprefs.getBoolean("RecentClearAllReposition", false)
		RecentClearAllLegacyStyle = Xprefs.getBoolean("RecentClearAllLegacyStyle", false)
	}

	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		val OverviewActionsViewClass = ReflectedClass.of("com.android.quickstep.views.OverviewActionsView")
		val RecentsViewClass = ReflectedClass.of("com.android.quickstep.views.RecentsView")
		val dismissAllTasksMethod: Method = findMethodBestMatch(RecentsViewClass.clazz, "dismissAllTasks", View::class.java)

		RecentsViewClass
			.afterConstruction()
			.run { param -> recentView = param.thisObject }

		RecentsViewClass
			.after("setColorTint")
			.run { param ->
				if (!RecentClearAllReposition || RecentClearAllLegacyStyle) return@run

				clearAllIcon?.drawable?.setTintList(getThemedColor(mContext))
			}

		RecentsViewClass
			.after("setVisibility")
			.run { param ->
				if (clearAllButton == null) return@run

				clearAllButton!!.visibility = param.args[0] as Int
			}

		OverviewActionsViewClass
			.before("onFinishInflate")
			.run { param ->
				if (!RecentClearAllReposition) return@run

				clearAllButton = FrameLayout(mContext)

				clearAllIcon = ImageView(mContext)
				clearAllIcon!!.setImageDrawable(ResourcesCompat.getDrawable(XPLauncher.moduleResources!!, R.drawable.ic_clear_all, mContext.theme))

				val params: FrameLayout.LayoutParams
				if (RecentClearAllLegacyStyle) {
					// Round look: 44dp circle, 60% black fill, white icon, 10dp padding, 8dp elevation.
					val size = dp(44f)
					val padding = dp(10f)

					clearAllIcon!!.drawable.setTintList(ColorStateList.valueOf(Color.WHITE))
					clearAllIcon!!.setPadding(padding, padding, padding, padding)

					val background = GradientDrawable()
					background.shape = GradientDrawable.OVAL
					background.setColor(0x99000000.toInt())
					clearAllButton!!.background = background
					clearAllButton!!.elevation = dp(8f).toFloat()

					clearAllButton!!.addView(clearAllIcon, FrameLayout.LayoutParams(size, size))
					params = FrameLayout.LayoutParams(size, size)
					params.rightMargin = dp(16f)
				} else {
					clearAllIcon!!.drawable.setTintList(getThemedColor(mContext))
					clearAllButton!!.addView(clearAllIcon)
					params = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
					params.rightMargin = dp(16f)
				}

				params.gravity = Gravity.END or Gravity.CENTER_VERTICAL

				clearAllButton!!.layoutParams = params
				clearAllButton!!.isClickable = true
				clearAllButton!!.setOnClickListener { v ->
					if (recentView != null) {
						try {
							dismissAllTasksMethod.invoke(recentView, v)
						} catch (ignored: Throwable) {
						}
					}
				}

				val parent = param.thisObject as FrameLayout
				parent.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT //resize to whole screen
				parent.addView(clearAllButton)
				clearAllButton!!.visibility = View.GONE
			}
	}

	private fun dp(value: Float): Int =
		TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, Resources.getSystem().displayMetrics).toInt()

	companion object {
		private var RecentClearAllReposition = false
		private var RecentClearAllLegacyStyle = false

		@JvmStatic
		fun getThemedColor(context: Context): ColorStateList? = getSystemAttrColor(
			context,
			if (SystemUtils.isDarkMode()) {
				android.R.attr.textColorPrimaryInverse
			} else {
				android.R.attr.textColorPrimary
			},
		)

		@JvmStatic
		fun getSystemAttrColor(context: Context, attr: Int): ColorStateList? =
			context.obtainStyledAttributes(intArrayOf(attr)).use { a ->
				a.getColorStateList(a.getIndex(0))
			}
	}
}
