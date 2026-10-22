PKGNAME="sh.anirudhgupta109.pxn"
LEGACY_PKGNAME="sh.siava.pixelxpert"
LSPDDBPATH="/data/adb/lspd/config/modules_config.db" 
MAGISKDBPATH="/data/adb/magisk.db" 
MODDIR=${0%/*} 
 
prepareSQL(){ 
	chmod +x $MODDIR/sqlite3
	SQLITEPATH="$MODDIR/sqlite3" 
} 
 
# runSQL "database path" "command" - then you can use $SQLRESULT to read the outcome 
runSQL(){ 
	SQLRESULT=$($SQLITEPATH $DBPATH "$CMD") 
} 
 
#grant silent root access to given UID 
grantRootUID(){ 
	if [ -z "$1" ]; then
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
	echo "- 	Granting root access to $1..." 
	PKG_UID=$(pm list packages -U "$1" --user 0 | grep ":$1 " | awk -F 'uid:' '{ print $2 }' | cut -d ',' -f 1)

	grantRootUID "$PKG_UID" "$1"
} 
 
#grant root access to required apps 
grantRootApps(){ 
	grantRootPkg $PKGNAME
}

prepareSQL 
 
if [ -n "$MAGISK_VER_CODE" ] && [ "$KSU" != "true" ] && [ "$APATCH" != "true" ]; then
	grantRootApps
fi

# Wait for boot to finish
until [ "$(getprop sys.boot_completed)" = "1" ]; do
	sleep 1
done

if pm list packages | grep -q "package:$LEGACY_PKGNAME"; then
	echo "- Removing legacy $LEGACY_PKGNAME..."
	pm uninstall --user 0 "$LEGACY_PKGNAME" > /dev/null 2>&1
fi

# Retry of a failed flash-time install. The flag only goes once pm reports
# success, so a refused install (e.g. a signature mismatch the user still has
# to resolve) is tried again on the next boot. pm output lands in install.log.
if [ -f "$MODDIR/install_needed" ]; then
	TMP_APK="/data/local/tmp/PixelXpert-Next.apk"
	INSTALL_OUT=""
	if cp -f "$MODDIR/PixelXpert-Next.apk" "$TMP_APK" 2>/dev/null; then
		chmod 0644 "$TMP_APK"
		INSTALL_OUT=$(pm install -r -d "$TMP_APK" 2>&1)
		rm -f "$TMP_APK"
	fi
	if ! echo "$INSTALL_OUT" | grep -q "Success"; then
		INSTALL_OUT=$(pm install -r -d "$MODDIR/PixelXpert-Next.apk" 2>&1)
	fi
	echo "$(date): $INSTALL_OUT" >> "$MODDIR/install.log"
	if echo "$INSTALL_OUT" | grep -q "Success"; then
		rm -f "$MODDIR/install_needed"
	fi
fi

# Give the system a brief moment to settle
sleep 2

# Restart SystemUI so the LSPosed hook can properly initiate with decrypted preferences
killall com.android.systemui