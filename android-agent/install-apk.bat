@echo off
REM Android Automation Agent Install Script for Windows
REM Usage: install-apk.bat [apk-file] [device-id]

setlocal enabledelayedexpansion

set APK_FILE=%1
set DEVICE_ID=%2

if "%APK_FILE%"=="" set APK_FILE=output\automation-agent-dev-debug.apk

echo ==========================================
echo Android Automation Agent - Install Script
echo ==========================================
echo.

REM Check if ADB is available
where adb >nul 2>nul
if errorlevel 1 (
    echo Error: ADB not found. Please install Android SDK Platform Tools.
    exit /b 1
)

REM Check if APK exists
if not exist "%APK_FILE%" (
    echo Error: APK file not found: %APK_FILE%
    echo.
    echo Available APKs:
    dir /s /b *.apk 2>nul
    exit /b 1
)

REM List connected devices
echo Connected devices:
adb devices -l
echo.

REM Build ADB command
set ADB_CMD=adb
if not "%DEVICE_ID%"=="" (
    set ADB_CMD=adb -s %DEVICE_ID%
    echo Using device: %DEVICE_ID%
)

REM Uninstall existing app (ignore errors)
echo Uninstalling existing app (if any)...
%ADB_CMD% uninstall com.automation.agent 2>nul
%ADB_CMD% uninstall com.automation.agent.dev 2>nul
%ADB_CMD% uninstall com.automation.agent.debug 2>nul
%ADB_CMD% uninstall com.automation.agent.dev.debug 2>nul

REM Install APK
echo Installing: %APK_FILE%
%ADB_CMD% install -r "%APK_FILE%"

if errorlevel 1 (
    echo.
    echo Installation failed!
    exit /b 1
)

echo.
echo ==========================================
echo Installation Successful!
echo ==========================================
echo.

REM Ask to launch
set /p LAUNCH="Launch app now? (y/n): "
if /i "%LAUNCH%"=="y" (
    echo Launching app...
    %ADB_CMD% shell am start -n com.automation.agent/.MainActivity
)

echo.
echo Done!
pause

