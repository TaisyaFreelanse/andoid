#!/bin/bash

# Android Automation Agent Install Script
# Usage: ./install-apk.sh [apk-file] [device-id]

set -e

APK_FILE=${1:-"output/automation-agent-dev-debug.apk"}
DEVICE_ID=$2

echo "=========================================="
echo "Android Automation Agent - Install Script"
echo "=========================================="
echo ""

# Check if ADB is available
if ! command -v adb &> /dev/null; then
    echo "Error: ADB not found. Please install Android SDK Platform Tools."
    exit 1
fi

# Check if APK exists
if [ ! -f "$APK_FILE" ]; then
    echo "Error: APK file not found: $APK_FILE"
    echo ""
    echo "Available APKs:"
    find . -name "*.apk" 2>/dev/null
    exit 1
fi

# List connected devices
echo "Connected devices:"
adb devices -l
echo ""

# Build ADB command
ADB_CMD="adb"
if [ -n "$DEVICE_ID" ]; then
    ADB_CMD="adb -s $DEVICE_ID"
    echo "Using device: $DEVICE_ID"
fi

# Uninstall existing app (ignore errors)
echo "Uninstalling existing app (if any)..."
$ADB_CMD uninstall com.automation.agent 2>/dev/null || true
$ADB_CMD uninstall com.automation.agent.dev 2>/dev/null || true
$ADB_CMD uninstall com.automation.agent.debug 2>/dev/null || true
$ADB_CMD uninstall com.automation.agent.dev.debug 2>/dev/null || true

# Install APK
echo "Installing: $APK_FILE"
$ADB_CMD install -r "$APK_FILE"

if [ $? -eq 0 ]; then
    echo ""
    echo "=========================================="
    echo "Installation Successful!"
    echo "=========================================="
    
    # Get package name from APK
    PACKAGE_NAME=$(aapt dump badging "$APK_FILE" 2>/dev/null | grep package | awk -F"'" '{print $2}')
    if [ -z "$PACKAGE_NAME" ]; then
        PACKAGE_NAME="com.automation.agent"
    fi
    
    echo "Package: $PACKAGE_NAME"
    echo ""
    
    # Ask to launch
    read -p "Launch app now? (y/n): " LAUNCH
    if [ "$LAUNCH" == "y" ] || [ "$LAUNCH" == "Y" ]; then
        echo "Launching app..."
        $ADB_CMD shell am start -n "$PACKAGE_NAME/.MainActivity"
    fi
else
    echo ""
    echo "Installation failed!"
    exit 1
fi

echo ""
echo "Done!"

