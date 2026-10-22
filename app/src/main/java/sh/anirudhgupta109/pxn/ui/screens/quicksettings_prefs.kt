package sh.anirudhgupta109.pxn.ui.screens

import sh.anirudhgupta109.pxn.R
import sh.anirudhgupta109.pxn.ui.prefs.PrefScreen
import sh.anirudhgupta109.pxn.ui.prefs.node.*

val quicksettings_prefs: PrefScreen = PrefScreen(
	key = "quicksettings_prefs",
	nodes = listOf(
			Illustration(key = "quick_settings_illustration", lottieRawRes = R.raw.lottie_quick_settings, ignore = true),
			Category(
	title = R.string.quick_settings_tiles_category,
	ignore = true,
	children = listOf(
				SliderPref(key = "UnMuteVolumePCT", title = R.string.qs_volume_unmute_title, default = "50", continuous = true),
				NavRow(key = "qs_tile_qty", title = R.string.qs_tile_qty_title, route = "qstile_qty"),
				SliderPref(key = "QSLabelScaleFactor", title = R.string.qs_tile_label_size, default = "0", min = -50f, max = 50f, step = 5f, continuous = true),
				SliderPref(key = "QSSecondaryLabelScaleFactor", title = R.string.qs_tile_secondary_label_size, default = "0", min = -50f, max = 50f, step = 5f, continuous = true),
	),
			),
			Category(
	title = R.string.qs_brightness_category,
	ignore = true,
	children = listOf(
				NavRow(key = "qs_gestures_moved", title = R.string.qs_pulldown_category, summary = R.string.gestures_moved_summary, route = "gesture_statusbar"),
				SwitchPref(key = "qs_brightness_slider_bottom", title = R.string.qs_brightness_slider_bottom_title, summary = R.string.qs_brightness_slider_bottom_desc, default = "false"),
				SwitchPref(key = "qqs_brightness_slider", title = R.string.qqs_brightness_slider_title, summary = R.string.qqs_brightness_slider_desc, default = "false"),
				SwitchPref(key = "QSAutoBrightnessToggle", title = R.string.QS_auto_brightness_title, summary = R.string.QS_auto_brightness_desc, default = "false"),
	),
			),
	),
)
