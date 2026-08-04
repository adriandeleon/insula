#!/usr/bin/env bash
# Builds the native bundle. Called from the `dist` profile; args: <target dir> <version> <type>.
#
# jpackage jlinks the runtime itself from the staged module path, so this script's only real job
# is assembling that path: the moditect-patched jars replace their automatic originals, and the
# project's own jar joins them.
set -euo pipefail

# Maven passes native paths, so on Windows this arrives as D:\a\...\target. bash reads those
# backslashes as escapes, and a glob like "$TARGET"/modules/*.jar then quietly matches nothing.
# Forward slashes are understood by bash and by the Windows tools jpackage drives — unlike the
# /d/a/... form git-bash would give, which jpackage rejects outright.
TARGET="${1//\\//}"
VERSION="$2"
TYPE="${3:-app-image}"
MODULES="$TARGET/dist-modules"
# The BitTorrent native, unpacked by maven-dependency-plugin. It cannot travel as a module — its
# jar's automatic module name is underivable — so it rides along as plain app content and is
# handed to jlibtorrent's own -Djlibtorrent.jni.path hook.
NATIVES="$TARGET/natives"

# jpackage rejects a version with a qualifier, and a SNAPSHOT build still needs to package.
APP_VERSION="${VERSION%%-*}"

# What jpackage is *told*, which is not always the truth. On macOS it refuses an app-version whose
# first number is zero — "The first number in an app-version cannot be zero or negative" — so every
# 0.x release would fail there while Linux and Windows package it happily. The leading zero is
# bumped just far enough to satisfy the validator, and the real version is written back into
# Info.plist below, so nothing a user can see ever carries the placeholder.
BUNDLE_VERSION="$APP_VERSION"
MAC_BUMPED=0
if [ "$(uname -s)" = "Darwin" ]; then
  case "$APP_VERSION" in
    0.*)
      BUNDLE_VERSION="1.${APP_VERSION#0.}"
      MAC_BUMPED=1
      echo "macOS rejects app-version $APP_VERSION; building as $BUNDLE_VERSION and correcting the plist"
      ;;
  esac
fi

# Puts the true version back and re-seals the bundle. jpackage ad-hoc-signs the .app during the
# app-image build and Info.plist is part of what that signature seals, so editing it afterwards
# makes macOS reject the app as tampered — Gatekeeper offers only "Move to Trash". Re-signing is
# not optional tidying.
fix_mac_version() {
  local app="$1" plist="$1/Contents/Info.plist"
  /usr/libexec/PlistBuddy -c "Set :CFBundleShortVersionString $APP_VERSION" "$plist"
  /usr/libexec/PlistBuddy -c "Set :CFBundleVersion $APP_VERSION" "$plist"
  codesign --force --deep --sign - "$app"
  echo "Info.plist set to $APP_VERSION and the bundle re-signed"
}

cp "$TARGET"/modules/*.jar "$MODULES"/ 2>/dev/null || true
# Any earlier build's app jar goes first. Without this, packaging after a version change without a
# clean leaves both jars staged and jpackage stops with "Two versions of module com.insula found",
# which reads as a module-path problem rather than as the leftover file it is.
rm -f "$MODULES"/insula-*.jar
# Windows jpackage will not accept a bash path; everything else here is portable.
cp "$TARGET"/insula-"$VERSION".jar "$MODULES"/

rm -rf "$TARGET/dist"
mkdir -p "$TARGET/dist"

ARGS=(
  --name Insula
  --app-version "$BUNDLE_VERSION"
  --module-path "$MODULES"
  --module com.insula/com.insula.app.Main
  --description "Offline reader for ZIM archives"
  --vendor Insula
  # zstd loads its bundled native library; without this the JVM warns on every archive opened.
  --java-options "--enable-native-access=com.github.luben.zstd_jni"
)

# jpackage wants a different format per platform, and silently ships its generic Java icon if the
# file is missing — so the icon is only passed when the one for this platform is actually there.
case "$(uname -s)" in
  Darwin) ICON="branding/insula.icns" ;;
  MINGW*|MSYS*|CYGWIN*) ICON="branding/insula.ico" ;;
  *)      ICON="branding/insula.png" ;;
esac
if [ -f "$ICON" ]; then
  ARGS+=(--icon "$ICON")
else
  echo "no icon at $ICON; the bundle will use jpackage's default"
fi

# BitTorrent is optional: a build whose host had no matching native profile simply ships without.
TORRENT_NATIVE="$(find "$NATIVES" -type f \( -name '*jlibtorrent*.so' -o -name '*jlibtorrent*.dylib' -o -name '*jlibtorrent*.dll' \) 2>/dev/null | head -1)"
if [ -n "$TORRENT_NATIVE" ]; then
  rm -rf "$TARGET/app-content"
  mkdir -p "$TARGET/app-content"
  cp "$TORRENT_NATIVE" "$TARGET/app-content/"
  ARGS+=(
    --input "$TARGET/app-content"
    # $APPDIR is expanded by the launcher, not by this shell — hence the single quotes.
    --java-options '-Djlibtorrent.jni.path=$APPDIR/'"$(basename "$TORRENT_NATIVE")"
  )
  echo "bundling BitTorrent native: $(basename "$TORRENT_NATIVE")"
else
  echo "no BitTorrent native staged; the build will ship without it"
fi

# Installer-only options: jpackage rejects them outright for a plain app-image. On the two-phase
# macOS path below these belong to the wrapping call, not to the app-image one, so they are held
# back rather than added here.
INSTALLER_ARGS=()
if [ "$TYPE" != "app-image" ]; then
  case "$(uname -s)" in
    Linux)
      ARGS+=(--linux-shortcut --linux-menu-group Education)
      # Our own .desktop template, for the StartupWMClass jpackage will not write. See the file.
      [ -d packaging/linux ] && ARGS+=(--resource-dir packaging/linux)
      ;;
    Darwin) INSTALLER_ARGS+=(--mac-package-name Insula) ;;
  esac
fi

if [ "$MAC_BUMPED" = "1" ]; then
  # Two phases, because there has to be a moment between "the .app exists" and "it is sealed into
  # a DMG" at which its Info.plist can be corrected. A single --type dmg call never offers one.
  STAGE="$TARGET/app-image"
  rm -rf "$STAGE"
  mkdir -p "$STAGE"
  echo "jpackage --type app-image (version $BUNDLE_VERSION, staging)"
  jpackage --type app-image --dest "$STAGE" "${ARGS[@]}"
  fix_mac_version "$STAGE/Insula.app"

  if [ "$TYPE" = "app-image" ]; then
    cp -R "$STAGE/Insula.app" "$TARGET/dist/"
  else
    # The wrapping call validates --app-version too, so it still gets the bumped one — but it does
    # not rewrite an Info.plist that is already correct, and it only uses the version to name the
    # file it writes, which the release workflow renames anyway.
    echo "jpackage --type $TYPE (wrapping the corrected app image)"
    jpackage --type "$TYPE" \
      --app-image "$STAGE/Insula.app" \
      --name Insula \
      --app-version "$BUNDLE_VERSION" \
      --dest "$TARGET/dist" \
      "${INSTALLER_ARGS[@]}"
  fi
else
  echo "jpackage --type $TYPE (version $BUNDLE_VERSION)"
  jpackage --type "$TYPE" --dest "$TARGET/dist" "${ARGS[@]}" "${INSTALLER_ARGS[@]}"
fi
ls -1 "$TARGET/dist"
