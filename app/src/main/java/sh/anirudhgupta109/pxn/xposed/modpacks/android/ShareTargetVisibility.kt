package sh.anirudhgupta109.pxn.xposed.modpacks.android

import android.content.Context
import de.robv.android.xposed.XposedHelpers.callMethod
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Method
import sh.anirudhgupta109.pxn.BuildConfig
import sh.anirudhgupta109.pxn.xposed.XPrefs.Xprefs
import sh.anirudhgupta109.pxn.xposed.XposedModPack
import sh.anirudhgupta109.pxn.xposed.annotations.FrameworkModPack
import sh.anirudhgupta109.pxn.xposed.utils.reflection.ReflectedClass

/**
 * Makes the module app visible to every app, so the "Copy content in
 * clipboard" share target (ClipboardShareActivity) can be started from any
 * of them.
 *
 * Since Android 14 the share sheet starts the chosen target on behalf of
 * the sharing app, and package visibility (Android 11) then resolves the
 * explicit component as that app. One that declares no `<queries>` matching
 * us - WhatsApp, for one - does not see the module, so the start fails with
 * START_CLASS_NOT_FOUND / ActivityNotFoundException although the sheet
 * lists the target. `android:forceQueryable` is honoured for system apps
 * only, so the package manager's AppsFilter is told here not to filter the
 * module out, while `ClipboardShareEnabled` is on.
 */
@FrameworkModPack
class ShareTargetVisibility(context: Context) : XposedModPack(context) {

	private var clipboardShareEnabled = true

	override fun onPreferenceUpdated(vararg key: String?) {
		clipboardShareEnabled = Xprefs.getBoolean("ClipboardShareEnabled", true)
	}

	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		// AppsFilterBase on 13+, AppsFilter before; the method may live in either.
		for (className in arrayOf("com.android.server.pm.AppsFilterBase", "com.android.server.pm.AppsFilter")) {
			val filterClass = ReflectedClass.ofIfPossible(className)
			val methods = filterClass.clazz?.declaredMethods?.filter { it.name == "shouldFilterApplication" } ?: continue
			for (method in methods) {
				val targetIndex = targetSettingIndex(method) ?: continue
				filterClass
					.after(method)
					.run { param ->
						if (!clipboardShareEnabled || param.getResult<Any?>() != true) return@run
						val packageName = runCatching {
							callMethod(param.args[targetIndex], "getPackageName")
						}.getOrNull()
						if (packageName == BuildConfig.APPLICATION_ID) param.setResult(false)
					}
			}
		}
	}

	/**
	 * shouldFilterApplication(snapshot, callingUid, Object callingSetting,
	 * PackageStateInternal targetPkgSetting, userId): the target is the last
	 * parameter typed as a package state / setting - the caller's is a bare
	 * Object.
	 */
	private fun targetSettingIndex(method: Method): Int? =
		method.parameterTypes.indices.lastOrNull { index ->
			val name = method.parameterTypes[index].name
			name.endsWith("PackageStateInternal") || name.endsWith("PackageSetting")
		}
}
