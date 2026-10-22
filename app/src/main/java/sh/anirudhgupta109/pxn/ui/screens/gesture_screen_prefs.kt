package sh.anirudhgupta109.pxn.ui.screens

import sh.anirudhgupta109.pxn.R
import sh.anirudhgupta109.pxn.ui.prefs.PrefScreen
import sh.anirudhgupta109.pxn.ui.prefs.node.*

val gesture_screen_prefs: PrefScreen = PrefScreen(
	key = "gesture_screen_prefs",
	nodes = listOf(
			Category(
	title = R.string.ls_general_options,
	ignore = true,
	children = listOf(
				SwitchPref(key = "DoubleTapSleepLockscreen", title = R.string.double_tap_sleep_title, default = "false", summaryOn = R.string.general_on, summaryOff = R.string.general_off, icon = R.drawable.ic_gesture_screen_double_tap),
				SwitchPref(key = "holdScreenTorchEnabled", title = R.string.so_taptaptorch_title, summary = R.string.so_taptaptorch_summary, default = "false", icon = R.drawable.ic_gesture_screen_long_press),
	),
			),
			Category(
	title = R.string.doze_header,
	ignore = true,
	children = listOf(
				SwitchPref(key = "doubleTapToWake", title = R.string.so_double_tap_wake_title, summary = R.string.so_double_tap_wake_summary, default = "false", icon = R.drawable.ic_gesture_screen_double_tap),
				SwitchPref(key = "TapToShowAmbient", title = R.string.so_tap_to_show_ambient_title, summary = R.string.so_tap_to_show_ambient_summary, default = "false", icon = R.drawable.ic_gesture_screen_tap),
				SwitchPref(key = "PickToShowAmbient", title = R.string.so_pick_to_show_ambient_title, summary = R.string.so_pick_to_show_ambient_summary, default = "false", icon = R.drawable.ic_gesture_pickup),
	),
			),
	),
)
