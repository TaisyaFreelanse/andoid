# Build APK and copy to target location
# Usage: .\build-and-copy-apk.ps1

param(
    [string]$TargetPath = "C:\Users\GameOn-DP\StudioProjects\andoid\android-agent\app\build\outputs\apk\dev\debug"
)

$ErrorActionPreference = "Stop"

Write-Host "`n🔨 Building APK..." -ForegroundColor Cyan
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Gray

# Change to android-agent directory
$androidAgentPath = "android-agent"
if (-not (Test-Path $androidAgentPath)) {
    Write-Host "❌ android-agent directory not found" -ForegroundColor Red
    exit 1
}

Push-Location $androidAgentPath

try {
    # Try to build using gradlew
    Write-Host "`n1️⃣ Building APK with Gradle..." -ForegroundColor Yellow
    
    if (Test-Path "gradlew.bat") {
        # Try without configuration cache first
        Write-Host "   Running: gradlew.bat assembleDevDebug --no-configuration-cache" -ForegroundColor Gray
        $buildOutput = .\gradlew.bat assembleDevDebug --no-configuration-cache 2>&1
        
        if ($LASTEXITCODE -ne 0) {
            Write-Host "   ⚠️  Build failed, trying clean build..." -ForegroundColor Yellow
            .\gradlew.bat clean 2>&1 | Out-Null
            $buildOutput = .\gradlew.bat assembleDevDebug --no-configuration-cache 2>&1
        }
        
        if ($LASTEXITCODE -ne 0) {
            Write-Host "   ❌ Gradle build failed" -ForegroundColor Red
            Write-Host "   Please build APK manually in Android Studio" -ForegroundColor Yellow
            Write-Host "   Or run: cd android-agent && .\gradlew.bat assembleDevDebug" -ForegroundColor Yellow
        } else {
            Write-Host "   ✅ Build successful!" -ForegroundColor Green
        }
    } else {
        Write-Host "   ⚠️  gradlew.bat not found" -ForegroundColor Yellow
        Write-Host "   Please build APK manually in Android Studio" -ForegroundColor Yellow
    }
    
    # Find APK
    Write-Host "`n2️⃣ Looking for APK..." -ForegroundColor Yellow
    $apkPaths = @(
        "app\build\outputs\apk\dev\debug\app-dev-debug.apk",
        "app\build\outputs\apk\dev\debug\*.apk",
        "app\build\outputs\apk\**\*.apk"
    )
    
    $foundApk = $null
    foreach ($path in $apkPaths) {
        $apk = Get-ChildItem -Path $path -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($apk) {
            $foundApk = $apk
            break
        }
    }
    
    if ($foundApk) {
        Write-Host "   ✅ Found APK: $($foundApk.FullName)" -ForegroundColor Green
        Write-Host "   Size: $([math]::Round($foundApk.Length / 1MB, 2)) MB" -ForegroundColor Gray
        
        # Copy to target
        Write-Host "`n3️⃣ Copying APK to target location..." -ForegroundColor Yellow
        $targetDir = Split-Path -Path $TargetPath -Parent
        $targetFile = Split-Path -Path $TargetPath -Leaf
        
        if (-not (Test-Path $targetDir)) {
            Write-Host "   Creating target directory: $targetDir" -ForegroundColor Gray
            New-Item -ItemType Directory -Path $targetDir -Force | Out-Null
        }
        
        $targetFullPath = Join-Path $targetDir $targetFile
        Copy-Item -Path $foundApk.FullName -Destination $targetFullPath -Force
        Write-Host "   ✅ Copied to: $targetFullPath" -ForegroundColor Green
        
    } else {
        Write-Host "   ❌ APK not found" -ForegroundColor Red
        Write-Host "   Please build APK manually:" -ForegroundColor Yellow
        Write-Host "   1. Open Android Studio" -ForegroundColor Gray
        Write-Host "   2. Open android-agent project" -ForegroundColor Gray
        Write-Host "   3. Build > Build Bundle(s) / APK(s) > Build APK(s)" -ForegroundColor Gray
        Write-Host "   4. Or run: cd android-agent && .\gradlew.bat assembleDevDebug" -ForegroundColor Gray
    }
    
} finally {
    Pop-Location
}

Write-Host "`n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Gray
Write-Host "✅ Done!" -ForegroundColor Green

