package sh.anirudhgupta109.pxn.ui.screens

import sh.anirudhgupta109.pxn.R
import sh.anirudhgupta109.pxn.ui.prefs.PrefScreen
import sh.anirudhgupta109.pxn.ui.prefs.node.*

val edge_common_prefs: PrefScreen = PrefScreen(
	key = "edge_common_prefs",
	nodes = listOf(
			Category(
	title = R.string.edge_behaviour_cat,
	ignore = true,
	children = listOf(
				SliderPref(key = "EdgeThickness", title = R.string.edge_thickness_title, default = "16", min = 4f, max = 48f, step = 1f, dependency = "EdgeGesturesEnabled"),
				SliderPref(key = "EdgeSwipeThreshold", title = R.string.edge_swipe_threshold_title, default = "40", min = 10f, max = 150f, step = 5f, dependency = "EdgeGesturesEnabled"),
				SliderPref(key = "EdgeLongSwipeThreshold", title = R.string.edge_long_swipe_threshold_title, default = "160", min = 60f, max = 400f, step = 10f, dependency = "EdgeGesturesEnabled"),
				SliderPref(key = "EdgeHoldTimeout", title = R.string.edge_hold_timeout_title, default = "500", min = 200f, max = 1500f, step = 50f, dependency = "EdgeGesturesEnabled"),
				SwitchPref(key = "EdgeHapticFeedback", title = R.string.edge_haptic_title, default = "true", dependency = "EdgeGesturesEnabled", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
				SwitchPref(key = "EdgeSoundEffect", title = R.string.edge_sound_title, default = "false", dependency = "EdgeGesturesEnabled", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
				SwitchPref(key = "EdgePriorityOverSystem", title = R.string.edge_priority_title, summary = R.string.edge_priority_summary, default = "true", dependency = "EdgeGesturesEnabled"),
				ListPref(key = "EdgeKeyboardBehavior", title = R.string.edge_keyboard_title, default = "2", entries = R.array.edge_keyboard_titles, entryValues = R.array.edge_keyboard_codes, dependency = "EdgeGesturesEnabled"),
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
				SwitchPref(key = "EdgeVisualEffect", title = R.string.edge_visual_effect_title, summary = R.string.edge_visual_effect_summary, default = "true", dependency = "EdgeGesturesEnabled"),
				ColorPref(key = "EdgeEffectColor", title = R.string.edge_effect_color_title, default = "-1607454672", dependency = "EdgeVisualEffect"),
				SliderPref(key = "EdgeWaveHeight", title = R.string.edge_wave_height_title, default = "34", min = 8f, max = 100f, step = 2f, dependency = "EdgeVisualEffect"),
				SliderPref(key = "EdgeWaveLength", title = R.string.edge_wave_length_title, default = "250", min = 60f, max = 500f, step = 10f, dependency = "EdgeVisualEffect"),
				SwitchPref(key = "EdgeShowActionIcon", title = R.string.edge_show_icon_title, summary = R.string.edge_show_icon_summary, default = "true", dependency = "EdgeGesturesEnabled"),
				ColorPref(key = "EdgeIconBgColor", title = R.string.edge_icon_bg_title, default = "-12627531", dependency = "EdgeShowActionIcon"),
				ColorPref(key = "EdgeIconFgColor", title = R.string.edge_icon_fg_title, default = "-1", dependency = "EdgeShowActionIcon"),
				SwitchPref(key = "EdgeShowActionLabel", title = R.string.edge_show_label_title, summary = R.string.edge_show_label_summary, default = "false", dependency = "EdgeGesturesEnabled"),
				SwitchPref(key = "EdgeDiagonalRange", title = R.string.edge_diagonal_range_title, summary = R.string.edge_diagonal_range_summary, default = "false", dependency = "EdgeGesturesEnabled"),
	),
			),
	),
)
