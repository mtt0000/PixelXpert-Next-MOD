package sh.anirudhgupta109.pxn.ui.screens

import sh.anirudhgupta109.pxn.R
import sh.anirudhgupta109.pxn.ui.prefs.PrefScreen
import sh.anirudhgupta109.pxn.ui.prefs.node.*

val edge_bottom_prefs: PrefScreen = PrefScreen(
	key = "edge_bottom_prefs",
	nodes = listOf(
			Category(
	title = R.string.edge_bottom_cat,
	ignore = true,
	children = listOf(
				SwitchPref(key = "EdgeBottomEnabled", title = R.string.edge_side_enabled_title, default = "false", dependency = "EdgeGesturesEnabled", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
				ListPref(key = "EdgeBottomOrientation", title = R.string.edge_orientation_title, default = "3", entries = R.array.edge_orientation_titles, entryValues = R.array.edge_orientation_codes, dependency = "EdgeGesturesEnabled"),
				SliderPref(key = "EdgeBottomLength", title = R.string.edge_width_title, default = "60", min = 10f, max = 100f, step = 1f, dependency = "EdgeGesturesEnabled"),
				SliderPref(key = "EdgeBottomPosition", title = R.string.edge_hposition_title, default = "50", min = 0f, max = 100f, step = 1f, dependency = "EdgeGesturesEnabled"),
				SliderPref(key = "EdgeBottomOffset", title = R.string.edge_bottom_offset_title, default = "0", min = 0f, max = 200f, step = 2f, dependency = "EdgeGesturesEnabled"),
				ListPref(key = "EdgeBottomAdjust", title = R.string.edge_adjust_title, summary = R.string.edge_adjust_summary, default = "0", entries = R.array.edge_adjust_titles, entryValues = R.array.edge_adjust_codes, dependency = "EdgeGesturesEnabled"),
	),
			),
			Category(
	title = R.string.edge_taps_cat,
	ignore = true,
	children = listOf(
				ActionPref(key = "EdgeBottomTap", title = R.string.edge_gesture_tap, default = "-1", dependency = "EdgeGesturesEnabled", icon = R.drawable.ic_gesture_tap),
				ActionPref(key = "EdgeBottomDoubleTap", title = R.string.edge_gesture_double_tap, default = "-1", dependency = "EdgeGesturesEnabled", icon = R.drawable.ic_gesture_double_tap),
				ActionPref(key = "EdgeBottomTripleTap", title = R.string.edge_gesture_triple_tap, default = "-1", dependency = "EdgeGesturesEnabled", icon = R.drawable.ic_gesture_triple_tap),
				ActionPref(key = "EdgeBottomLongPress", title = R.string.edge_gesture_long_press, default = "-1", dependency = "EdgeGesturesEnabled", icon = R.drawable.ic_gesture_long_press),
	),
			),
			Category(
	title = R.string.edge_swipe_in_cat,
	ignore = true,
	children = listOf(
				ActionPref(key = "EdgeBottomSwipeIn", title = R.string.edge_gesture_swipe_in, default = "-1", dependency = "EdgeGesturesEnabled", icon = R.drawable.ic_gesture_bottom_in),
				ActionPref(key = "EdgeBottomSwipeInHold", title = R.string.edge_gesture_swipe_in_hold, default = "-1", dependency = "EdgeGesturesEnabled", icon = R.drawable.ic_gesture_bottom_in_hold),
				ActionPref(key = "EdgeBottomSwipeInOut", title = R.string.edge_gesture_swipe_in_out, default = "-1", dependency = "EdgeGesturesEnabled", icon = R.drawable.ic_gesture_bottom_in_out),
				ActionPref(key = "EdgeBottomLongSwipeIn", title = R.string.edge_gesture_long_swipe_in, default = "-1", dependency = "EdgeGesturesEnabled", icon = R.drawable.ic_gesture_bottom_long_in),
	),
			),
			Category(
	title = R.string.edge_swipe_left_cat,
	ignore = true,
	children = listOf(
				ActionPref(key = "EdgeBottomSwipeUp", title = R.string.edge_gesture_swipe_left, default = "-1", dependency = "EdgeGesturesEnabled", icon = R.drawable.ic_gesture_bottom_up),
				ActionPref(key = "EdgeBottomSwipeUpHold", title = R.string.edge_gesture_swipe_left_hold, default = "-1", dependency = "EdgeGesturesEnabled", icon = R.drawable.ic_gesture_bottom_up_hold),
				ActionPref(key = "EdgeBottomSwipeUpDown", title = R.string.edge_gesture_swipe_left_right, default = "-1", dependency = "EdgeGesturesEnabled", icon = R.drawable.ic_gesture_bottom_up_down),
	),
			),
			Category(
	title = R.string.edge_swipe_right_cat,
	ignore = true,
	children = listOf(
				ActionPref(key = "EdgeBottomSwipeDown", title = R.string.edge_gesture_swipe_right, default = "-1", dependency = "EdgeGesturesEnabled", icon = R.drawable.ic_gesture_bottom_down),
				ActionPref(key = "EdgeBottomSwipeDownHold", title = R.string.edge_gesture_swipe_right_hold, default = "-1", dependency = "EdgeGesturesEnabled", icon = R.drawable.ic_gesture_bottom_down_hold),
				ActionPref(key = "EdgeBottomSwipeDownUp", title = R.string.edge_gesture_swipe_right_left, default = "-1", dependency = "EdgeGesturesEnabled", icon = R.drawable.ic_gesture_bottom_down_up),
	),
			),
			Category(
	title = R.string.edge_diagonal_cat,
	ignore = true,
	children = listOf(
				ActionPref(key = "EdgeBottomSwipeDiagUp", title = R.string.edge_gesture_diag_left, default = "-1", dependency = "EdgeGesturesEnabled", icon = R.drawable.ic_gesture_bottom_diag_up),
				ActionPref(key = "EdgeBottomSwipeDiagDown", title = R.string.edge_gesture_diag_right, default = "-1", dependency = "EdgeGesturesEnabled", icon = R.drawable.ic_gesture_bottom_diag_down),
				ActionPref(key = "EdgeBottomLongSwipeDiagUp", title = R.string.edge_gesture_long_diag_left, default = "-1", dependency = "EdgeGesturesEnabled", icon = R.drawable.ic_gesture_bottom_long_diag_up),
				ActionPref(key = "EdgeBottomLongSwipeDiagDown", title = R.string.edge_gesture_long_diag_right, default = "-1", dependency = "EdgeGesturesEnabled", icon = R.drawable.ic_gesture_bottom_long_diag_down),
	),
			),
			Category(
	title = R.string.gesture_nav_extra_cat,
	ignore = true,
	children = listOf(
				SwitchPref(key = "FCLongSwipeEnabled", title = R.string.gesture_nav_long_swipe_fc_title, default = "false", icon = R.drawable.ic_gesture_bottom_long_in, summaryOn = R.string.general_on, summaryOff = R.string.general_off),
				ActionPref(key = "leftSwipeUpAction", title = R.string.gesture_nav_left_swipe_up_title, default = "-1", icon = R.drawable.ic_gesture_bottom_in),
				SliderPref(key = "leftSwipeUpPercentage", title = R.string.gesture_nav_left_swipe_up_percent, default = "25", min = 5f, max = 40f, step = 1f, valueCount = 1),
				ActionPref(key = "rightSwipeUpAction", title = R.string.gesture_nav_right_swipe_up_title, default = "-1", icon = R.drawable.ic_gesture_bottom_in),
				SliderPref(key = "rightSwipeUpPercentage", title = R.string.gesture_nav_right_swipe_up_percent, default = "25", min = 5f, max = 40f, step = 1f, valueCount = 1),
				ActionPref(key = "twoFingerSwipeUpAction", title = R.string.gesture_nav_two_finger_title, default = "-1", icon = R.drawable.ic_gesture_bottom_in),
				SliderPref(key = "swipeUpPercentage", title = R.string.gesture_nav_swipe_up_height, default = "20", min = 5f, max = 50f, step = 5f, valueCount = 1),
	),
			),
	),
)
