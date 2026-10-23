package sh.anirudhgupta109.pxn.ui.screens

import sh.anirudhgupta109.pxn.R
import sh.anirudhgupta109.pxn.ui.prefs.PrefScreen
import sh.anirudhgupta109.pxn.ui.prefs.node.*

val gesture_nav_prefs: PrefScreen = PrefScreen(
	key = "gesture_nav_prefs",
	nodes = listOf(
			Illustration(key = "system_nav_fully_gestural_illustration", lottieRawRes = R.raw.lottie_system_nav_fully_gestural, ignore = true),
			Category(
	title = R.string.edge_gestures_title,
	ignore = true,
	children = listOf(
				SwitchPref(key = "EdgeGesturesEnabled", title = R.string.edge_gestures_enable_title, summary = R.string.edge_gestures_summary, default = "false", prominent = true),
				NavRow(key = "edge_left", title = R.string.edge_left_cat, summary = R.string.edge_side_nav_summary, route = "edge_left", icon = R.drawable.ic_gesture_left_in),
				NavRow(key = "edge_right", title = R.string.edge_right_cat, summary = R.string.edge_side_nav_summary, route = "edge_right", icon = R.drawable.ic_gesture_right_in),
				NavRow(key = "edge_bottom", title = R.string.edge_bottom_cat, summary = R.string.edge_bottom_nav_summary, route = "edge_bottom", icon = R.drawable.ic_gesture_bottom_in),
				NavRow(key = "edge_common", title = R.string.edge_common_cat, summary = R.string.edge_common_summary, route = "edge_common"),
				NavRow(key = "gesture_backup", title = R.string.gesture_backup_title, summary = R.string.gesture_backup_summary, route = "gesture_backup"),
	),
			),
			Category(
	title = R.string.other_gestures_cat,
	ignore = true,
	children = listOf(
				NavRow(key = "gesture_statusbar", title = R.string.gesture_statusbar_title, summary = R.string.gesture_statusbar_summary, route = "gesture_statusbar", icon = R.drawable.ic_gesture_top_in),
				NavRow(key = "gesture_screen", title = R.string.gesture_screen_title, summary = R.string.gesture_screen_summary, route = "gesture_screen", icon = R.drawable.ic_gesture_screen_double_tap),
				NavRow(key = "remap_physical_buttons", title = R.string.remap_physical_buttons_title, summary = R.string.physical_buttons_summary, route = "physical_button_remap"),
	),
			),
			Category(
	title = R.string.gesture_nav_pill_cat,
	ignore = true,
	children = listOf(
				SwitchPref(key = "navPillColorAccent", title = R.string.colorpill, default = "false", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
				SliderPref(key = "GesPillWidthModPos", title = R.string.gesture_nav_pill_width_title, default = "50"),
				SliderPref(key = "GesPillHeightFactor", title = R.string.gesture_nav_pill_height_title, default = "100", min = 50f, max = 300f),
	),
			),
			Category(
	title = R.string.keyboard_space_cat,
	ignore = true,
	children = listOf(
				SwitchPref(key = "KeyboardSpaceHideButtons", title = R.string.keyboard_space_hide_buttons_title, default = "false", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
	),
			),
			SwitchPref(key = "HideNavbarInsets", title = R.string.hide_navbar_insets_title, default = "false", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
	),
)
