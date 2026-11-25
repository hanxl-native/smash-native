#!/bin/sh
# Clones Peanuts Native at the pinned release, copies this app's files in and
# applies the hooks patch. Needs git, JDK 17 and the Android SDK (NDK 28.2).
set -e
TAG=v0.1.7-1
HERE=$(cd "$(dirname "$0")" && pwd)
DIR=${1:-$HERE/peanuts-native}

if [ ! -d "$DIR" ]; then
    git clone --depth 1 --branch "$TAG" https://github.com/igawa6/peanuts-native "$DIR"
fi
git -C "$DIR" apply "$HERE/patches/0001-smash-hooks.patch"
git -C "$DIR" apply "$HERE/patches/0002-256-inputs.patch"
cp -R "$HERE/overlay/." "$DIR/"

echo "Ready: $DIR"
echo "Build with:"
echo "  cd \"$DIR/android/app\""
echo "  JAVA_HOME=/path/to/jdk17 ANDROID_HOME=/path/to/android-sdk \\"
echo "    ./gradlew apk -PnxApp=peanuts -PnxAppId=smash -PnxLabel=\"Smash Ultimate Native\" \\"
echo "    -PnxGames=01006A800016E000 -PnxPackEngine=fake"
