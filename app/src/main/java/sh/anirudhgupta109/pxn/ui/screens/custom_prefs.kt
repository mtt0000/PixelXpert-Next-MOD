package sh.anirudhgupta109.pxn.ui.screens

import sh.anirudhgupta109.pxn.R
import sh.anirudhgupta109.pxn.ui.prefs.PrefScreen
import sh.anirudhgupta109.pxn.ui.prefs.node.*

val custom_prefs: PrefScreen = PrefScreen(
	key = "custom_prefs",
	nodes = listOf(
			Category(
	title = R.string.custom_share_category,
	ignore = true,
	children = listOf(
				SwitchPref(key = "ClipboardShareEnabled", title = R.string.clipboard_share_title, summary = R.string.clipboard_share_summary, default = "true"),
	),
			),
			Category(
	title = R.string.custom_recents_category,
	ignore = true,
	children = listOf(
				SwitchPref(key = "RecentClearAllLegacyStyle", title = R.string.clear_all_legacy_style_title, summary = R.string.clear_all_legacy_style_summary, default = "false", dependency = "RecentClearAllReposition"),
				SwitchPref(key = "RecentClearAllInActionBar", title = R.string.clear_all_in_action_bar_title, summary = R.string.clear_all_in_action_bar_summary, default = "false", dependency = "RecentClearAllReposition"),
				SwitchPref(key = "RecentKillApp", title = R.string.kill_app_title, summary = R.string.kill_app_summary, default = "true"),
				SwitchPref(key = "RecentHideApp", title = R.string.hide_app_title, summary = R.string.hide_app_summary, default = "true"),
				SwitchPref(key = "RecentHiddenAppsInActionBar", title = R.string.hidden_apps_in_action_bar_title, summary = R.string.hidden_apps_in_action_bar_summary, default = "false", dependency = "RecentHideApp"),
				NavRow(key = "hidden_apps", title = R.string.hidden_apps_title, summary = R.string.hidden_apps_summary, route = "hidden_apps"),
	),
			),
	),
)
