#!/usr/bin/env bash
# Builds the native bundle. Called from the `dist` profile; args: <target dir> <version> <type>.
#
# jpackage jlinks the runtime itself from the staged module path, so this script's only real job
# is assembling that path: the moditect-patched jars replace their automatic originals, and the
# project's own jar joins them.
set -euo pipefail

TARGET="$1"
VERSION="$2"
TYPE="${3:-app-image}"
MODULES="$TARGET/dist-modules"
# The BitTorrent native, unpacked by maven-dependency-plugin. It cannot travel as a module — its
# jar's automatic module name is underivable — so it rides along as plain app content and is
# handed to jlibtorrent's own -Djlibtorrent.jni.path hook.
NATIVES="$TARGET/natives"

# jpackage rejects a version with a qualifier, and a SNAPSHOT build still needs to package.
APP_VERSION="${VERSION%%-*}"

cp "$TARGET"/modules/*.jar "$MODULES"/ 2>/dev/null || true
# Windows jpackage will not accept a bash path; everything else here is portable.
cp "$TARGET"/insula-"$VERSION".jar "$MODULES"/

rm -rf "$TARGET/dist"
mkdir -p "$TARGET/dist"

ARGS=(
  --type "$TYPE"
  --name Insula
  --app-version "$APP_VERSION"
  --module-path "$MODULES"
  --module com.insula/com.insula.app.Main
  --dest "$TARGET/dist"
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

# Installer-only options: jpackage rejects them outright for a plain app-image.
if [ "$TYPE" != "app-image" ]; then
  case "$(uname -s)" in
    Linux)
      ARGS+=(--linux-shortcut --linux-menu-group Education)
      # Our own .desktop template, for the StartupWMClass jpackage will not write. See the file.
      [ -d packaging/linux ] && ARGS+=(--resource-dir packaging/linux)
      ;;
    Darwin) ARGS+=(--mac-package-name Insula) ;;
  esac
fi

echo "jpackage --type $TYPE (version $APP_VERSION)"
jpackage "${ARGS[@]}"
ls -1 "$TARGET/dist"
