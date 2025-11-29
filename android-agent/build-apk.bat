@echo off
REM Android Automation Agent Build Script for Windows
REM Usage: build-apk.bat [debug|release] [dev|staging|prod]

setlocal enabledelayedexpansion

set BUILD_TYPE=%1
set FLAVOR=%2

if "%BUILD_TYPE%"=="" set BUILD_TYPE=debug
if "%FLAVOR%"=="" set FLAVOR=dev

echo ==========================================
echo Android Automation Agent - Build Script
echo ==========================================
echo Build Type: %BUILD_TYPE%
echo Flavor: %FLAVOR%
echo.

REM Check if gradlew.bat exists
if not exist "gradlew.bat" (
    echo Error: gradlew.bat not found. Run this script from the android-agent directory.
    exit /b 1
)

REM Clean previous builds
echo Cleaning previous builds...
call gradlew.bat clean

REM Build based on type and flavor
REM Capitalize first letter of flavor and build type
set FLAVOR_CAP=%FLAVOR%
set BUILD_CAP=%BUILD_TYPE%

REM Simple capitalization (works for dev, staging, prod, debug, release)
if "%FLAVOR%"=="dev" set FLAVOR_CAP=Dev
if "%FLAVOR%"=="staging" set FLAVOR_CAP=Staging
if "%FLAVOR%"=="prod" set FLAVOR_CAP=Prod
if "%BUILD_TYPE%"=="debug" set BUILD_CAP=Debug
if "%BUILD_TYPE%"=="release" set BUILD_CAP=Release

set TASK_NAME=assemble%FLAVOR_CAP%%BUILD_CAP%
echo Running: gradlew.bat %TASK_NAME%
call gradlew.bat %TASK_NAME%

if errorlevel 1 (
    echo Build failed!
    exit /b 1
)

REM Find the APK
set APK_DIR=app\build\outputs\apk\%FLAVOR%\%BUILD_TYPE%
echo.
echo ==========================================
echo Build Successful!
echo ==========================================
echo APK Location: %APK_DIR%
echo.

REM Copy to output directory
if not exist "output" mkdir output
for %%f in (%APK_DIR%\*.apk) do (
    copy "%%f" "output\automation-agent-%FLAVOR%-%BUILD_TYPE%.apk"
    echo Copied to: output\automation-agent-%FLAVOR%-%BUILD_TYPE%.apk
)

echo.
echo Done!
pause

