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
	title = R.string.custom_screenshot_category,
	ignore = true,
	children = listOf(
				SwitchPref(key = "disableScreenshotSound", title = R.string.screenshot_sound_disable_title, default = "false", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
				SwitchPref(key = "ScreenshotChordInsecure", title = R.string.screenshot_insecure, default = "false", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
				SwitchPref(key = "ScreenshotDeleteShared", title = R.string.screenshot_delete_shared_title, summary = R.string.screenshot_delete_shared_summary, default = "false"),
				SliderPref(key = "ScreenshotDeleteSharedDelay", title = R.string.screenshot_delete_shared_delay_title, summary = R.string.screenshot_delete_shared_delay_summary, default = "60", min = 10f, max = 600f, step = 10f, valueCount = 1, dependency = "ScreenshotDeleteShared"),
				SwitchPref(key = "ScreenshotDeleteButton", title = R.string.screenshot_delete_button_title, summary = R.string.screenshot_delete_button_summary, default = "false"),
				SwitchPref(key = "ScreenshotAutoScroll", title = R.string.screenshot_auto_scroll_title, summary = R.string.screenshot_auto_scroll_summary, default = "false"),
				SwitchPref(key = "ScreenshotAutoScrollManual", title = R.string.screenshot_auto_scroll_manual_title, summary = R.string.screenshot_auto_scroll_manual_summary, default = "true", dependency = "ScreenshotAutoScroll"),
				SliderPref(key = "ScreenshotAutoScrollMaxSteps", title = R.string.screenshot_auto_scroll_steps_title, summary = R.string.screenshot_auto_scroll_steps_summary, default = "10", min = 2f, max = 25f, step = 1f, valueCount = 1, dependency = "ScreenshotAutoScroll"),
	),
			),
			Category(
	title = R.string.custom_recents_category,
	ignore = true,
	children = listOf(
				SwitchPref(key = "RecentClearAllReposition", title = R.string.clear_all_reposition_title, default = "false", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
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
