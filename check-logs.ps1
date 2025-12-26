# Скрипт для просмотра логов Android Agent
# Использование: .\check-logs.ps1

Write-Host "=== Android Agent Log Viewer ===" -ForegroundColor Cyan
Write-Host ""

# Проверка наличия adb
$adbPath = Get-Command adb -ErrorAction SilentlyContinue
if (-not $adbPath) {
    Write-Host "ОШИБКА: adb не найден в PATH!" -ForegroundColor Red
    Write-Host "Установите Android SDK Platform Tools или добавьте путь к adb в PATH" -ForegroundColor Yellow
    exit 1
}

Write-Host "Проверка подключенных устройств..." -ForegroundColor Yellow
$devices = adb devices 2>&1 | Select-String -Pattern "device$"
if ($devices) {
    Write-Host "✓ Устройство подключено" -ForegroundColor Green
    Write-Host ""
    Write-Host "Запуск logcat с фильтрацией..." -ForegroundColor Yellow
    Write-Host "Нажмите Ctrl+C для остановки" -ForegroundColor Gray
    Write-Host ""
    Write-Host "=== ЛОГИ ===" -ForegroundColor Cyan
    Write-Host ""
    
    # Очистка старого буфера logcat
    adb logcat -c
    
    # Запуск logcat с фильтрацией
    adb logcat | Select-String -Pattern "ControllerService|UNIQUENESS|TASK EXECUTION|LogInterceptor|AutoLogcatSender|App|MainActivity" | ForEach-Object {
        $line = $_.Line
        if ($line -match "ERROR|FATAL|Exception|Crash") {
            Write-Host $line -ForegroundColor Red
        } elseif ($line -match "WARN|Warning") {
            Write-Host $line -ForegroundColor Yellow
        } elseif ($line -match "INFO|Starting|Completed") {
            Write-Host $line -ForegroundColor Green
        } else {
            Write-Host $line -ForegroundColor White
        }
    }
} else {
    Write-Host "✗ Устройство не подключено!" -ForegroundColor Red
    Write-Host ""
    Write-Host "Инструкции:" -ForegroundColor Yellow
    Write-Host "1. Подключите устройство через USB" -ForegroundColor White
    Write-Host "2. Включите 'Отладка по USB' в настройках разработчика" -ForegroundColor White
    Write-Host "3. Разрешите отладку на устройстве при появлении запроса" -ForegroundColor White
    Write-Host "4. Запустите скрипт снова" -ForegroundColor White
}

