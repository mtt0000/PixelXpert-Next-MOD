# Install helpers for the PixelXpert-Next app, sourced by customize.sh (at
# flash time) and service.sh (boot-time retry). POSIX sh only: the managers
# run these under busybox ash.
#
# The caller defines pxn_say "<text>" for output and sets PKGNAME.
#
# An installed app is first updated in place, which keeps its data and its
# LSPosed/Vector entry (enabled state and scope). Only when that is refused -
# a build signed with another key (upstream release, older CI key) - is it
# uninstalled and the APK from the zip installed fresh. Uninstalling deletes
# the app's data, so its shared_prefs (all the module settings) are copied
# aside first and put back into the new install. PXN_REINSTALLED tells the
# caller which of the two happened.

PXN_DE_DIR="/data/user_de/0/$PKGNAME"
PXN_CE_DIR="/data/user/0/$PKGNAME"

pxnIsInstalled()
{
	pm path "$PKGNAME" 2>/dev/null | grep -q "^package:"
}

# pxnBackupSettings <backup dir>
pxnBackupSettings()
{
	rm -rf "$1"
	for PxnSide in de ce; do
		if [ "$PxnSide" = "de" ]; then PxnSrc="$PXN_DE_DIR"; else PxnSrc="$PXN_CE_DIR"; fi
		if [ -d "$PxnSrc/shared_prefs" ]; then
			mkdir -p "$1/$PxnSide"
			cp -f "$PxnSrc/shared_prefs/"* "$1/$PxnSide/" 2>/dev/null
		fi
	done
	if [ -d "$1" ]; then
		pxn_say "- Settings backed up"
	fi
}

# pxnRestoreSettings <backup dir>: copies the files back with the new app's
# owner and SELinux context. Returns 1 when the app's data dir is not there.
pxnRestoreSettings()
{
	[ -d "$1" ] || return 0
	PxnRestored=0
	for PxnSide in de ce; do
		[ -d "$1/$PxnSide" ] || continue
		if [ "$PxnSide" = "de" ]; then PxnDst="$PXN_DE_DIR"; else PxnDst="$PXN_CE_DIR"; fi
		[ -d "$PxnDst" ] || continue

		PxnUid=$(stat -c %u "$PxnDst")
		mkdir -p "$PxnDst/shared_prefs"
		cp -f "$1/$PxnSide/"* "$PxnDst/shared_prefs/"
		chown -R "$PxnUid:$PxnUid" "$PxnDst/shared_prefs"
		chmod 0771 "$PxnDst/shared_prefs"
		chmod 0660 "$PxnDst/shared_prefs/"*
		restorecon -R "$PxnDst/shared_prefs" 2>/dev/null
		PxnRestored=1
	done
	if [ "$PxnRestored" = "1" ]; then
		pxn_say "- Settings restored"
		return 0
	fi
	return 1
}

pxnUninstall()
{
	pxn_say "- Removing the installed app to install the one in the zip..."
	PxnOut=$(pm uninstall "$PKGNAME" 2>&1)
	if ! echo "$PxnOut" | grep -q "Success"; then
		# A copy on the system partition cannot be uninstalled outright.
		PxnOut=$(pm uninstall --user 0 "$PKGNAME" 2>&1)
	fi
	if echo "$PxnOut" | grep -q "Success"; then
		pxn_say "- Old app removed"
		return 0
	fi
	pxn_say "  pm uninstall said: $PxnOut"
	return 1
}

# pxnInstallApk <apk>: sets PXN_INSTALL_OUT, returns 0 on success.
# system_server may not read files under /data/adb (SELinux), so a copy in
# /data/local/tmp is tried first, then the module path itself.
pxnInstallApk()
{
	PXN_INSTALL_OUT=""
	PxnTmp="/data/local/tmp/PixelXpert-Next.apk"
	if cp -f "$1" "$PxnTmp" 2>/dev/null; then
		chmod 0644 "$PxnTmp"
		PXN_INSTALL_OUT=$(pm install -r -d "$PxnTmp" 2>&1)
		rm -f "$PxnTmp"
	fi
	if ! echo "$PXN_INSTALL_OUT" | grep -q "Success"; then
		PXN_INSTALL_OUT=$(pm install -r -d "$1" 2>&1)
	fi
	# Some pm builds exit 0 on failure, so the output decides.
	echo "$PXN_INSTALL_OUT" | grep -q "Success"
}

# pxnReinstallApp <apk> <backup dir>: the whole sequence. Returns 0 when the
# app from the zip is installed; on failure the backup stays where it is, for
# the boot-time retry to restore.
pxnReinstallApp()
{
	PXN_REINSTALLED=0
	if pxnIsInstalled && pxnInstallApk "$1"; then
		pxn_say "- App updated in place, settings and LSPosed scope kept"
		# A backup still waiting from an earlier failed run is restored.
		if pxnRestoreSettings "$2"; then
			rm -rf "$2"
		fi
		return 0
	fi

	if pxnIsInstalled; then
		pxn_say "- In-place update refused (different signature?)"
		pxn_say "  pm said: $PXN_INSTALL_OUT"
		PXN_REINSTALLED=1
		# A backup still waiting for its restore is the older, real one.
		if [ ! -d "$2" ]; then
			pxnBackupSettings "$2"
		fi
		pxnUninstall
	fi

	if pxnInstallApk "$1"; then
		pxn_say "- App installed successfully!"
		if pxnRestoreSettings "$2"; then
			rm -rf "$2"
		fi
		return 0
	fi

	pxn_say "- Failed to install app!"
	pxn_say "  pm said: $PXN_INSTALL_OUT"
	return 1
}
