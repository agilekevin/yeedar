#!/bin/bash
set -e

MODS_DIR="/mnt/c/Users/zipwo/AppData/Roaming/ModrinthApp/profiles/1.21 gigawatts/mods"

echo "Building Yeedar..."
cd "$(dirname "$0")"
./gradlew build

echo "Removing old Yeedar JARs..."
rm -f "$MODS_DIR"/yeedar-*.jar

echo "Copying new JAR..."
cp build/libs/yeedar-*.jar "$MODS_DIR/"

echo "Done! Restart Minecraft to load the new version."
ls -la "$MODS_DIR"/yeedar-*.jar
