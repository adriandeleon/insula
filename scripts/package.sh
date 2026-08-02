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

# Installer-only options: jpackage rejects them outright for a plain app-image.
if [ "$TYPE" != "app-image" ]; then
  case "$(uname -s)" in
    Linux)  ARGS+=(--linux-shortcut --linux-menu-group Education) ;;
    Darwin) ARGS+=(--mac-package-name Insula) ;;
  esac
fi

echo "jpackage --type $TYPE (version $APP_VERSION)"
jpackage "${ARGS[@]}"
ls -1 "$TARGET/dist"
