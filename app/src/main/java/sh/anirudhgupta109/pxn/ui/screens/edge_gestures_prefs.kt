package sh.anirudhgupta109.pxn.ui.screens

import sh.anirudhgupta109.pxn.R
import sh.anirudhgupta109.pxn.ui.prefs.PrefScreen
import sh.anirudhgupta109.pxn.ui.prefs.node.*

val edge_gestures_prefs: PrefScreen = PrefScreen(
	key = "edge_gestures_prefs",
	nodes = listOf(
			SwitchPref(key = "EdgeGesturesEnabled", title = R.string.edge_gestures_enable_title, default = "false", mainSwitch = true),
			Category(
	title = R.string.edge_left_cat,
	ignore = true,
	children = listOf(
				SwitchPref(key = "EdgeLeftEnabled", title = R.string.edge_side_enabled_title, default = "true", dependency = "EdgeGesturesEnabled", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
				SliderPref(key = "EdgeLeftLength", title = R.string.edge_length_title, default = "50", min = 10f, max = 100f, step = 1f, dependency = "EdgeGesturesEnabled"),
				SliderPref(key = "EdgeLeftPosition", title = R.string.edge_position_title, default = "50", min = 0f, max = 100f, step = 1f, dependency = "EdgeGesturesEnabled"),
				ListPref(key = "EdgeLeftTap", title = R.string.edge_gesture_tap, default = "-1", entries = R.array.edge_action_titles, entryValues = R.array.edge_action_codes, dependency = "EdgeGesturesEnabled"),
				ListPref(key = "EdgeLeftDoubleTap", title = R.string.edge_gesture_double_tap, default = "-1", entries = R.array.edge_action_titles, entryValues = R.array.edge_action_codes, dependency = "EdgeGesturesEnabled"),
				ListPref(key = "EdgeLeftLongPress", title = R.string.edge_gesture_long_press, default = "-1", entries = R.array.edge_action_titles, entryValues = R.array.edge_action_codes, dependency = "EdgeGesturesEnabled"),
				ListPref(key = "EdgeLeftSwipeIn", title = R.string.edge_gesture_swipe_in, default = "2", entries = R.array.edge_action_titles, entryValues = R.array.edge_action_codes, dependency = "EdgeGesturesEnabled"),
				ListPref(key = "EdgeLeftSwipeUp", title = R.string.edge_gesture_swipe_up, default = "-1", entries = R.array.edge_action_titles, entryValues = R.array.edge_action_codes, dependency = "EdgeGesturesEnabled"),
				ListPref(key = "EdgeLeftSwipeDown", title = R.string.edge_gesture_swipe_down, default = "-1", entries = R.array.edge_action_titles, entryValues = R.array.edge_action_codes, dependency = "EdgeGesturesEnabled"),
	),
			),
			Category(
	title = R.string.edge_right_cat,
	ignore = true,
	children = listOf(
				SwitchPref(key = "EdgeRightEnabled", title = R.string.edge_side_enabled_title, default = "true", dependency = "EdgeGesturesEnabled", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
				SliderPref(key = "EdgeRightLength", title = R.string.edge_length_title, default = "50", min = 10f, max = 100f, step = 1f, dependency = "EdgeGesturesEnabled"),
				SliderPref(key = "EdgeRightPosition", title = R.string.edge_position_title, default = "50", min = 0f, max = 100f, step = 1f, dependency = "EdgeGesturesEnabled"),
				ListPref(key = "EdgeRightTap", title = R.string.edge_gesture_tap, default = "-1", entries = R.array.edge_action_titles, entryValues = R.array.edge_action_codes, dependency = "EdgeGesturesEnabled"),
				ListPref(key = "EdgeRightDoubleTap", title = R.string.edge_gesture_double_tap, default = "-1", entries = R.array.edge_action_titles, entryValues = R.array.edge_action_codes, dependency = "EdgeGesturesEnabled"),
				ListPref(key = "EdgeRightLongPress", title = R.string.edge_gesture_long_press, default = "-1", entries = R.array.edge_action_titles, entryValues = R.array.edge_action_codes, dependency = "EdgeGesturesEnabled"),
				ListPref(key = "EdgeRightSwipeIn", title = R.string.edge_gesture_swipe_in, default = "2", entries = R.array.edge_action_titles, entryValues = R.array.edge_action_codes, dependency = "EdgeGesturesEnabled"),
				ListPref(key = "EdgeRightSwipeUp", title = R.string.edge_gesture_swipe_up, default = "-1", entries = R.array.edge_action_titles, entryValues = R.array.edge_action_codes, dependency = "EdgeGesturesEnabled"),
				ListPref(key = "EdgeRightSwipeDown", title = R.string.edge_gesture_swipe_down, default = "-1", entries = R.array.edge_action_titles, entryValues = R.array.edge_action_codes, dependency = "EdgeGesturesEnabled"),
	),
			),
			Category(
	title = R.string.edge_common_cat,
	ignore = true,
	children = listOf(
				SliderPref(key = "EdgeThickness", title = R.string.edge_thickness_title, default = "16", min = 4f, max = 48f, step = 1f, dependency = "EdgeGesturesEnabled"),
				SliderPref(key = "EdgeSwipeThreshold", title = R.string.edge_swipe_threshold_title, default = "40", min = 10f, max = 150f, step = 5f, dependency = "EdgeGesturesEnabled"),
				SwitchPref(key = "EdgeHapticFeedback", title = R.string.edge_haptic_title, default = "true", dependency = "EdgeGesturesEnabled", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
				SwitchPref(key = "EdgePriorityOverSystem", title = R.string.edge_priority_title, summary = R.string.edge_priority_summary, default = "true", dependency = "EdgeGesturesEnabled"),
				SwitchPref(key = "EdgeDisableInLandscape", title = R.string.edge_disable_landscape_title, default = "false", dependency = "EdgeGesturesEnabled", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
				SwitchPref(key = "EdgeDisableOnHome", title = R.string.edge_disable_home_title, default = "false", dependency = "EdgeGesturesEnabled", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
				NavRow(key = "edge_excluded_apps", title = R.string.edge_excluded_apps_title, summary = R.string.edge_excluded_apps_summary, route = "edge_excluded_apps"),
	),
			),
			Category(
	title = R.string.edge_appearance_cat,
	ignore = true,
	children = listOf(
				SwitchPref(key = "EdgeShowHandle", title = R.string.edge_show_handle_title, default = "false", dependency = "EdgeGesturesEnabled", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
				ColorPref(key = "EdgeHandleColor", title = R.string.edge_handle_color_title, default = "-1", dependency = "EdgeShowHandle"),
				SliderPref(key = "EdgeHandleOpacity", title = R.string.edge_handle_opacity_title, default = "40", min = 5f, max = 100f, step = 5f, dependency = "EdgeShowHandle"),
	),
			),
	),
)
