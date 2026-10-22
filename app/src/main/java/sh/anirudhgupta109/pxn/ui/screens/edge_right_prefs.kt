package sh.anirudhgupta109.pxn.ui.screens

import sh.anirudhgupta109.pxn.R
import sh.anirudhgupta109.pxn.ui.prefs.PrefScreen
import sh.anirudhgupta109.pxn.ui.prefs.node.*

val edge_right_prefs: PrefScreen = PrefScreen(
	key = "edge_right_prefs",
	nodes = listOf(
			Category(
	title = R.string.edge_right_cat,
	ignore = true,
	children = listOf(
				SwitchPref(key = "EdgeRightEnabled", title = R.string.edge_side_enabled_title, default = "true", dependency = "EdgeGesturesEnabled", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
				ListPref(key = "EdgeRightOrientation", title = R.string.edge_orientation_title, default = "3", entries = R.array.edge_orientation_titles, entryValues = R.array.edge_orientation_codes, dependency = "EdgeGesturesEnabled"),
				SliderPref(key = "EdgeRightLength", title = R.string.edge_length_title, default = "50", min = 10f, max = 100f, step = 1f, dependency = "EdgeGesturesEnabled"),
				SliderPref(key = "EdgeRightPosition", title = R.string.edge_position_title, default = "50", min = 0f, max = 100f, step = 1f, dependency = "EdgeGesturesEnabled"),
				ListPref(key = "EdgeRightAdjust", title = R.string.edge_adjust_title, summary = R.string.edge_adjust_summary, default = "0", entries = R.array.edge_adjust_titles, entryValues = R.array.edge_adjust_codes, dependency = "EdgeGesturesEnabled"),
	),
			),
			Category(
	title = R.string.edge_taps_cat,
	ignore = true,
	children = listOf(
				ActionPref(key = "EdgeRightTap", title = R.string.edge_gesture_tap, default = "-1", dependency = "EdgeGesturesEnabled", icon = R.drawable.ic_gesture_tap),
				ActionPref(key = "EdgeRightDoubleTap", title = R.string.edge_gesture_double_tap, default = "-1", dependency = "EdgeGesturesEnabled", icon = R.drawable.ic_gesture_double_tap),
				ActionPref(key = "EdgeRightTripleTap", title = R.string.edge_gesture_triple_tap, default = "-1", dependency = "EdgeGesturesEnabled", icon = R.drawable.ic_gesture_triple_tap),
				ActionPref(key = "EdgeRightLongPress", title = R.string.edge_gesture_long_press, default = "-1", dependency = "EdgeGesturesEnabled", icon = R.drawable.ic_gesture_long_press),
	),
			),
			Category(
	title = R.string.edge_swipe_in_cat,
	ignore = true,
	children = listOf(
				ActionPref(key = "EdgeRightSwipeIn", title = R.string.edge_gesture_swipe_in, default = "2", dependency = "EdgeGesturesEnabled", icon = R.drawable.ic_gesture_right_in),
				ActionPref(key = "EdgeRightSwipeInHold", title = R.string.edge_gesture_swipe_in_hold, default = "-1", dependency = "EdgeGesturesEnabled", icon = R.drawable.ic_gesture_right_in_hold),
				ActionPref(key = "EdgeRightSwipeInOut", title = R.string.edge_gesture_swipe_in_out, default = "-1", dependency = "EdgeGesturesEnabled", icon = R.drawable.ic_gesture_right_in_out),
				ActionPref(key = "EdgeRightLongSwipeIn", title = R.string.edge_gesture_long_swipe_in, default = "-1", dependency = "EdgeGesturesEnabled", icon = R.drawable.ic_gesture_right_long_in),
	),
			),
			Category(
	title = R.string.edge_swipe_up_cat,
	ignore = true,
	children = listOf(
				ActionPref(key = "EdgeRightSwipeUp", title = R.string.edge_gesture_swipe_up, default = "-1", dependency = "EdgeGesturesEnabled", icon = R.drawable.ic_gesture_right_up),
				ActionPref(key = "EdgeRightSwipeUpHold", title = R.string.edge_gesture_swipe_up_hold, default = "-1", dependency = "EdgeGesturesEnabled", icon = R.drawable.ic_gesture_right_up_hold),
				ActionPref(key = "EdgeRightSwipeUpDown", title = R.string.edge_gesture_swipe_up_down, default = "-1", dependency = "EdgeGesturesEnabled", icon = R.drawable.ic_gesture_right_up_down),
	),
			),
			Category(
	title = R.string.edge_swipe_down_cat,
	ignore = true,
	children = listOf(
				ActionPref(key = "EdgeRightSwipeDown", title = R.string.edge_gesture_swipe_down, default = "-1", dependency = "EdgeGesturesEnabled", icon = R.drawable.ic_gesture_right_down),
				ActionPref(key = "EdgeRightSwipeDownHold", title = R.string.edge_gesture_swipe_down_hold, default = "-1", dependency = "EdgeGesturesEnabled", icon = R.drawable.ic_gesture_right_down_hold),
				ActionPref(key = "EdgeRightSwipeDownUp", title = R.string.edge_gesture_swipe_down_up, default = "-1", dependency = "EdgeGesturesEnabled", icon = R.drawable.ic_gesture_right_down_up),
	),
			),
			Category(
	title = R.string.edge_diagonal_cat,
	ignore = true,
	children = listOf(
				ActionPref(key = "EdgeRightSwipeDiagUp", title = R.string.edge_gesture_diag_up, default = "-1", dependency = "EdgeGesturesEnabled", icon = R.drawable.ic_gesture_right_diag_up),
				ActionPref(key = "EdgeRightSwipeDiagDown", title = R.string.edge_gesture_diag_down, default = "-1", dependency = "EdgeGesturesEnabled", icon = R.drawable.ic_gesture_right_diag_down),
				ActionPref(key = "EdgeRightLongSwipeDiagUp", title = R.string.edge_gesture_long_diag_up, default = "-1", dependency = "EdgeGesturesEnabled", icon = R.drawable.ic_gesture_right_long_diag_up),
				ActionPref(key = "EdgeRightLongSwipeDiagDown", title = R.string.edge_gesture_long_diag_down, default = "-1", dependency = "EdgeGesturesEnabled", icon = R.drawable.ic_gesture_right_long_diag_down),
	),
			),
			Category(
	title = R.string.back_gesture_category_title,
	ignore = true,
	children = listOf(
				SwitchPref(key = "BackFromRight", title = R.string.back_gesture_on_edge_title, default = "true", icon = R.drawable.ic_gesture_right_in),
				SliderPref(key = "BackRightHeight", title = R.string.back_height_title, default = "100", min = 10f, max = 100f, continuous = true),
	),
			),
	),
)
