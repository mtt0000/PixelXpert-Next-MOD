package sh.anirudhgupta109.pxn.ui.screens

import sh.anirudhgupta109.pxn.R
import sh.anirudhgupta109.pxn.ui.prefs.PrefScreen
import sh.anirudhgupta109.pxn.ui.prefs.node.*

val gesture_statusbar_prefs: PrefScreen = PrefScreen(
	key = "gesture_statusbar_prefs",
	nodes = listOf(
			Category(
	title = R.string.qs_pulldown_category,
	ignore = true,
	children = listOf(
				SwitchPref(key = "QSPullodwnEnabled", title = R.string.qs_pulldown_switch_title, default = "false", summaryOn = R.string.general_on, summaryOff = R.string.general_off, icon = R.drawable.ic_gesture_top_in),
				SliderPref(key = "QSPulldownPercent", title = R.string.qs_pulldown_size, default = "25", continuous = true),
				ListPref(key = "QSPulldownSide", title = R.string.qs_pulldown_side_title, default = "1", entries = R.array.qs_pulldown_side_names, entryValues = R.array.qs_pulldown_side_left),
				SwitchPref(key = "oneFingerPullupEnabled", title = R.string.qs_pullup_switch_title, default = "false", summaryOn = R.string.general_on, summaryOff = R.string.general_off, icon = R.drawable.ic_gesture_top_out),
	),
			),
			Category(
	title = R.string.gesture_statusbar_title,
	ignore = true,
	children = listOf(
				SwitchPref(key = "DoubleTapSleep", title = R.string.double_tap_sleep_title, default = "false", summaryOn = R.string.general_on, summaryOff = R.string.general_off, icon = R.drawable.ic_gesture_top_double_tap),
				SwitchPref(key = "StatusbarLongpressAppSwitch", title = R.string.statusbar_long_press_app_switch, default = "false", summaryOn = R.string.general_on, summaryOff = R.string.general_off, icon = R.drawable.ic_gesture_top_long_press),
	),
			),
	),
)
