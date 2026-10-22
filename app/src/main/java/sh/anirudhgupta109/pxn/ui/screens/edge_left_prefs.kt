package sh.anirudhgupta109.pxn.ui.screens

import sh.anirudhgupta109.pxn.R
import sh.anirudhgupta109.pxn.ui.prefs.PrefScreen
import sh.anirudhgupta109.pxn.ui.prefs.node.*

val edge_left_prefs: PrefScreen = PrefScreen(
	key = "edge_left_prefs",
	nodes = listOf(
			Category(
	title = R.string.edge_left_cat,
	ignore = true,
	children = listOf(
				SwitchPref(key = "EdgeLeftEnabled", title = R.string.edge_side_enabled_title, default = "true", dependency = "EdgeGesturesEnabled", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
				ListPref(key = "EdgeLeftOrientation", title = R.string.edge_orientation_title, default = "3", entries = R.array.edge_orientation_titles, entryValues = R.array.edge_orientation_codes, dependency = "EdgeGesturesEnabled"),
				SliderPref(key = "EdgeLeftLength", title = R.string.edge_length_title, default = "50", min = 10f, max = 100f, step = 1f, dependency = "EdgeGesturesEnabled"),
				SliderPref(key = "EdgeLeftPosition", title = R.string.edge_position_title, default = "50", min = 0f, max = 100f, step = 1f, dependency = "EdgeGesturesEnabled"),
				ListPref(key = "EdgeLeftAdjust", title = R.string.edge_adjust_title, summary = R.string.edge_adjust_summary, default = "0", entries = R.array.edge_adjust_titles, entryValues = R.array.edge_adjust_codes, dependency = "EdgeGesturesEnabled"),
	),
			),
			Category(
	title = R.string.edge_taps_cat,
	ignore = true,
	children = listOf(
				ActionPref(key = "EdgeLeftTap", title = R.string.edge_gesture_tap, default = "-1", dependency = "EdgeGesturesEnabled"),
				ActionPref(key = "EdgeLeftDoubleTap", title = R.string.edge_gesture_double_tap, default = "-1", dependency = "EdgeGesturesEnabled"),
				ActionPref(key = "EdgeLeftTripleTap", title = R.string.edge_gesture_triple_tap, default = "-1", dependency = "EdgeGesturesEnabled"),
				ActionPref(key = "EdgeLeftLongPress", title = R.string.edge_gesture_long_press, default = "-1", dependency = "EdgeGesturesEnabled"),
	),
			),
			Category(
	title = R.string.edge_swipe_in_cat,
	ignore = true,
	children = listOf(
				ActionPref(key = "EdgeLeftSwipeIn", title = R.string.edge_gesture_swipe_in, default = "2", dependency = "EdgeGesturesEnabled"),
				ActionPref(key = "EdgeLeftSwipeInHold", title = R.string.edge_gesture_swipe_in_hold, default = "-1", dependency = "EdgeGesturesEnabled"),
				ActionPref(key = "EdgeLeftSwipeInOut", title = R.string.edge_gesture_swipe_in_out, default = "-1", dependency = "EdgeGesturesEnabled"),
				ActionPref(key = "EdgeLeftLongSwipeIn", title = R.string.edge_gesture_long_swipe_in, default = "-1", dependency = "EdgeGesturesEnabled"),
	),
			),
			Category(
	title = R.string.edge_swipe_up_cat,
	ignore = true,
	children = listOf(
				ActionPref(key = "EdgeLeftSwipeUp", title = R.string.edge_gesture_swipe_up, default = "-1", dependency = "EdgeGesturesEnabled"),
				ActionPref(key = "EdgeLeftSwipeUpHold", title = R.string.edge_gesture_swipe_up_hold, default = "-1", dependency = "EdgeGesturesEnabled"),
				ActionPref(key = "EdgeLeftSwipeUpDown", title = R.string.edge_gesture_swipe_up_down, default = "-1", dependency = "EdgeGesturesEnabled"),
	),
			),
			Category(
	title = R.string.edge_swipe_down_cat,
	ignore = true,
	children = listOf(
				ActionPref(key = "EdgeLeftSwipeDown", title = R.string.edge_gesture_swipe_down, default = "-1", dependency = "EdgeGesturesEnabled"),
				ActionPref(key = "EdgeLeftSwipeDownHold", title = R.string.edge_gesture_swipe_down_hold, default = "-1", dependency = "EdgeGesturesEnabled"),
				ActionPref(key = "EdgeLeftSwipeDownUp", title = R.string.edge_gesture_swipe_down_up, default = "-1", dependency = "EdgeGesturesEnabled"),
	),
			),
			Category(
	title = R.string.edge_diagonal_cat,
	ignore = true,
	children = listOf(
				ActionPref(key = "EdgeLeftSwipeDiagUp", title = R.string.edge_gesture_diag_up, default = "-1", dependency = "EdgeGesturesEnabled"),
				ActionPref(key = "EdgeLeftSwipeDiagDown", title = R.string.edge_gesture_diag_down, default = "-1", dependency = "EdgeGesturesEnabled"),
				ActionPref(key = "EdgeLeftLongSwipeDiagUp", title = R.string.edge_gesture_long_diag_up, default = "-1", dependency = "EdgeGesturesEnabled"),
				ActionPref(key = "EdgeLeftLongSwipeDiagDown", title = R.string.edge_gesture_long_diag_down, default = "-1", dependency = "EdgeGesturesEnabled"),
	),
			),
	),
)
