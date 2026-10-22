PKGNAME="sh.anirudhgupta109.pxn"
LEGACY_PKGNAME="sh.siava.pixelxpert"
LSPDDBPATH="/data/adb/lspd/config/modules_config.db"
MAGISKDBPATH="/data/adb/magisk.db"


prepareSQL(){
	unzip $ZIPFILE sqlite3 -d $TMPDIR/ > /dev/null
	chmod +x $TMPDIR/sqlite3

	SQLITEPATH="$TMPDIR/sqlite3"
}

# runSQL "database path" "command" - then you can use $SQLRESULT to read the outcome
runSQL(){
	SQLRESULT=$($SQLITEPATH $DBPATH "$CMD")
}

#grant silent root access to given UID
grantRootUID(){
	if [ -z "$1" ]; then
		ui_print "- Error: No UID provided for root grant"
		return 1
	fi
	DBPATH=$MAGISKDBPATH
	
	#new record - older magisk compatibility
	CMD="insert into policies (uid, package_name, policy, until, logging, notification) values ($1, '$2', 2, 0, 1, 0);" && runSQL
	#new record
	CMD="insert into policies (uid, policy, until, logging, notification) values ($1, 2, 0, 1, 0);" && runSQL
	#previously present record
	CMD="update policies set policy = 2, until = 0, logging = 1, notification = 0 where uid = $1;" && runSQL
}


#grant root access to given package name
grantRootPkg(){
	ui_print "- 	Granting root access to $1..."
	PKG_UID=$(pm list packages -U "$1" --user 0 | grep ":$1 " | awk -F 'uid:' '{ print $2 }' | cut -d ',' -f 1)

	grantRootUID "$PKG_UID" "$1"
}

#grant root access to required apps
grantRootApps(){
	grantRootPkg $PKGNAME
}

# Stock Pixel ROM check. Tips and the Pixel Launcher are both stock-only
# priv-apps; either one is enough, since a later release can rename one.
assertPixelRom()
{
	for PixelAppDir in /product/priv-app /system_ext/priv-app /system/priv-app; do
		for PixelAppPattern in "TipsPrebuilt*" "NexusLauncher*"; do
			if find "$PixelAppDir" -maxdepth 1 -name "$PixelAppPattern" -print 2>/dev/null | grep -q .; then
				return 0
			fi
		done
	done

	ui_print 'Device does not seem to be a Pixel'
	ui_print 'phone, containing an original ROM.'

	abort 'Installation aborted due to incompatibility'
}

# The app is built with minSdk 37, so anything older than Android 17 would
# only fail later at pm install. Preview builds report the previous SDK level,
# so a 17 preview is recognised by its build id (C = Android 17, Z = preview
# drops) instead. The old Android 16 QPR pattern ([BCZ][DP][1-5]) both let
# Android 16 through, where the APK cannot install, and capped the release
# digit at 5.
assertCompatibleBuild()
{
	SDK_LEVEL=$(getprop ro.build.version.sdk)
	BUILD_ID=$(getprop ro.build.id)
	CODENAME=$(getprop ro.build.version.codename)

	if [ -n "$SDK_LEVEL" ] && [ "$SDK_LEVEL" -ge 37 ] 2>/dev/null; then
		return 0
	fi
	if [ "$CODENAME" != "REL" ] && echo "$BUILD_ID" | grep -qE '^[CZ][DP][0-9]'; then
		return 0
	fi

	ui_print "This build needs Android 17 (API 37);"
	ui_print "found SDK $SDK_LEVEL, build $BUILD_ID."
	ui_print 'Please install the stable'
	ui_print 'version 4.3.x instead'

	abort 'Installation aborted due to incompatibility'
}


assertPixelRom

assertCompatibleBuild





prepareSQL

ui_print ''
ui_print ''

if [ -n "$MAGISK_VER_CODE" ] && [ "$KSU" != "true" ] && [ "$APATCH" != "true" ]; then
	grantRootApps
else
	ui_print "- Manual intervention required!!!!"
	ui_print "- Please grant root manually to PixelXpert-Next."
	ui_print "- (You may need to enable 'Show system apps' in your root manager)"
fi

set_perm $MODPATH/service.sh 0 0 0755
set_perm $MODPATH/pxn_app.sh 0 0 0644

ui_print ''
ui_print ''

# The legacy build can sit on the system partition, where a plain uninstall is
# refused; --user 0 covers that as well as a regular user install.
if pm list packages | grep -q "package:$LEGACY_PKGNAME"; then
	ui_print "- Removing legacy $LEGACY_PKGNAME..."
	pm uninstall --user 0 "$LEGACY_PKGNAME" > /dev/null 2>&1
fi

ui_print '- Installing PixelXpert-Next app...'
APK_PATH="$MODPATH/PixelXpert-Next.apk"
pxn_say() { ui_print "$1"; }
. "$MODPATH/pxn_app.sh"
if [ -f "$APK_PATH" ]; then
	# Any installed copy is removed and the zip's APK installed fresh (see
	# pxn_app.sh); settings are carried over. The backup lives in the module
	# folder so the boot-time retry can still restore it.
	if ! pxnReinstallApp "$APK_PATH" "$MODPATH/settings_backup"; then
		ui_print "- Will retry on boot."
		touch "$MODPATH/install_needed"
	fi
	ui_print ''
	ui_print '- If PixelXpert-Next was enabled in LSPosed before,'
	ui_print '  enable it again (with its scope) after reboot:'
	ui_print '  removing the old app clears its LSPosed entry.'
else
	ui_print "- APK not found in zip!"
fi

# A system build of this package is wiped by Android on the next boot, once the
# system folder it lived in stops being mounted. The APK has to go back on at
# that point, so the boot service keeps the retry flag for it.
if pm list packages -s | grep -q "package:$PKGNAME"; then
	touch "$MODPATH/install_needed"
fi

if [ -f "$LSPDDBPATH" ]; then
	ui_print ''
	ui_print ''
	ui_print 'Installation Complete!'
	ui_print 'Please Reboot your device to activate'
else
	ui_print 'Lsposed not found!!'
	ui_print 'This module will not work without Lsposed'
	ui_print 'Please:'
	ui_print '- Install Lsposed'
	ui_print '- Reboot'
#	ui_print '- Manually enable PixelXpert-Next in Lsposed'
#	ui_print '- Reboot'
fi

	ui_print ''
	ui_print '  ***************************'
	ui_print '  * Brought to you by:      *'
	ui_print '  *                         *'
	ui_print '  * PixelXpert-Next team    *'
	ui_print '  ***************************'
	ui_print ''
