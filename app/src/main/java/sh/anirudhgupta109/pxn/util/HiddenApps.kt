package sh.anirudhgupta109.pxn.util

/**
 * Shared contract for the "hidden Recents cards" list.
 *
 * The list lives in the module preferences (`<applicationId>_preferences`) as one
 * comma-separated string of package names, so that BOTH sides can edit it:
 * the Pixel Launcher hook (HideAppMod) and the module UI (HiddenAppsScreen).
 */
object HiddenApps {

	/** Preference key holding the comma-separated package list. */
	const val PREF_KEY: String = "HiddenAppsList"

	fun parse(csv: String?): List<String> =
		csv.orEmpty()
			.split(',')
			.map { it.trim() }
			.filter { it.isNotEmpty() }
			.distinct()

	fun join(packages: Collection<String>): String = packages.joinToString(",")
}
