package sh.anirudhgupta109.pxn.ui.screens

import sh.anirudhgupta109.pxn.R
import sh.anirudhgupta109.pxn.ui.prefs.PrefScreen
import sh.anirudhgupta109.pxn.ui.prefs.node.*

/**
 * Physical keys: every press of Power, Volume up and Volume down, with the
 * screen on and off, and the two-key combinations, each set to an action
 * from the gestures' list, "Default" (Android's own behaviour) or "Do
 * nothing". Read in system server by ScreenOffKeys; replaces the older
 * long/double press lists, migrated by migrateKeyRemapPrefs.
 */
val physical_buttons_prefs: PrefScreen = PrefScreen(
	key = "physical_buttons_prefs",
	nodes = listOf(
			Illustration(key = "button_illustration", lottieRawRes = R.raw.lottie_button, ignore = true),
			Category(
	title = R.string.key_category_power,
	ignore = true,
	children = listOf(
				ActionPref(key = "KeyPowerLongOn", title = R.string.key_long_on, default = "-1", keyMode = true),
				ActionPref(key = "KeyPowerDoubleOn", title = R.string.key_double_on, default = "-1", keyMode = true),
				ActionPref(key = "KeyPowerLongOff", title = R.string.key_long_off, default = "-1", keyMode = true),
				ActionPref(key = "KeyPowerDoubleOff", title = R.string.key_double_off, default = "-1", keyMode = true),
	),
			),
			Category(
	title = R.string.key_category_vol_up,
	ignore = true,
	children = listOf(
				ActionPref(key = "KeyVolUpSingleOn", title = R.string.key_single_on, default = "-1", keyMode = true),
				ActionPref(key = "KeyVolUpDoubleOn", title = R.string.key_double_on, summary = R.string.key_double_summary, default = "-1", keyMode = true),
				ActionPref(key = "KeyVolUpLongOn", title = R.string.key_long_on, default = "-1", keyMode = true),
				ActionPref(key = "KeyVolUpSingleOff", title = R.string.key_single_off, default = "-1", keyMode = true),
				ActionPref(key = "KeyVolUpDoubleOff", title = R.string.key_double_off, summary = R.string.key_double_summary, default = "-1", keyMode = true),
				ActionPref(key = "KeyVolUpLongOff", title = R.string.key_long_off, default = "-1", keyMode = true),
	),
			),
			Category(
	title = R.string.key_category_vol_down,
	ignore = true,
	children = listOf(
				ActionPref(key = "KeyVolDownSingleOn", title = R.string.key_single_on, default = "-1", keyMode = true),
				ActionPref(key = "KeyVolDownDoubleOn", title = R.string.key_double_on, summary = R.string.key_double_summary, default = "-1", keyMode = true),
				ActionPref(key = "KeyVolDownLongOn", title = R.string.key_long_on, default = "-1", keyMode = true),
				ActionPref(key = "KeyVolDownSingleOff", title = R.string.key_single_off, default = "-1", keyMode = true),
				ActionPref(key = "KeyVolDownDoubleOff", title = R.string.key_double_off, summary = R.string.key_double_summary, default = "-1", keyMode = true),
				ActionPref(key = "KeyVolDownLongOff", title = R.string.key_long_off, default = "-1", keyMode = true),
	),
			),
			Category(
	title = R.string.key_category_combos,
	ignore = true,
	children = listOf(
				ActionPref(key = "KeyComboPowerVolDown", title = R.string.key_combo_power_vol_down, summary = R.string.key_combo_summary, default = "-1", keyMode = true),
				ActionPref(key = "KeyComboPowerVolUp", title = R.string.key_combo_power_vol_up, summary = R.string.key_combo_summary, default = "-1", keyMode = true),
				ActionPref(key = "KeyComboVolUpDown", title = R.string.key_combo_vol_up_down, summary = R.string.key_combo_summary, default = "-1", keyMode = true),
	),
			),
			Category(
	title = R.string.key_category_flashlight,
	ignore = true,
	children = listOf(
				SwitchPref(key = "controlFlashWithVolKeys", title = R.string.control_flashlight_with_volume_keys, summary = R.string.control_flashlight_with_volume_keys_summary, default = "false"),
	),
			),
	),
)
