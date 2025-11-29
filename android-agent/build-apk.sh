#!/bin/bash

# Android Automation Agent Build Script
# Usage: ./build-apk.sh [debug|release] [dev|staging|prod]

set -e

BUILD_TYPE=${1:-debug}
FLAVOR=${2:-dev}

echo "=========================================="
echo "Android Automation Agent - Build Script"
echo "=========================================="
echo "Build Type: $BUILD_TYPE"
echo "Flavor: $FLAVOR"
echo ""

# Check if gradlew exists
if [ ! -f "./gradlew" ]; then
    echo "Error: gradlew not found. Run this script from the android-agent directory."
    exit 1
fi

# Make gradlew executable
chmod +x ./gradlew

# Clean previous builds
echo "Cleaning previous builds..."
./gradlew clean

# Build based on type and flavor
TASK_NAME="assemble${FLAVOR^}${BUILD_TYPE^}"
echo "Running: ./gradlew $TASK_NAME"
./gradlew $TASK_NAME

# Find the APK
APK_DIR="app/build/outputs/apk/$FLAVOR/$BUILD_TYPE"
APK_FILE=$(find $APK_DIR -name "*.apk" 2>/dev/null | head -1)

if [ -z "$APK_FILE" ]; then
    echo "Error: APK not found in $APK_DIR"
    exit 1
fi

echo ""
echo "=========================================="
echo "Build Successful!"
echo "=========================================="
echo "APK Location: $APK_FILE"
echo "APK Size: $(du -h "$APK_FILE" | cut -f1)"
echo ""

# Optional: Copy to output directory
OUTPUT_DIR="./output"
mkdir -p $OUTPUT_DIR
cp "$APK_FILE" "$OUTPUT_DIR/automation-agent-$FLAVOR-$BUILD_TYPE.apk"
echo "Copied to: $OUTPUT_DIR/automation-agent-$FLAVOR-$BUILD_TYPE.apk"

# If release build, show signing info
if [ "$BUILD_TYPE" == "release" ]; then
    echo ""
    echo "Verifying APK signature..."
    if command -v apksigner &> /dev/null; then
        apksigner verify --print-certs "$APK_FILE"
    else
        echo "apksigner not found. Install Android SDK Build Tools to verify signature."
    fi
fi

echo ""
echo "Done!"

