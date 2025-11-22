

$baseUrl = "http://localhost:3000"
$errors = @()

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Тестирование Android Automation API" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""


Write-Host "[1/8] Проверка health endpoint..." -ForegroundColor Yellow
try {
    $health = Invoke-WebRequest -Uri "$baseUrl/health" -UseBasicParsing
    $healthData = $health.Content | ConvertFrom-Json
    if ($healthData.status -eq "ok") {
        Write-Host "OK Health check passed" -ForegroundColor Green
    } else {
        throw "Health check failed"
    }
} catch {
    Write-Host "FAIL Health check failed: $_" -ForegroundColor Red
    $errors += "Health check failed"
}


Write-Host "[2/8] Тестирование входа в систему..." -ForegroundColor Yellow
$token = $null
try {
    $loginBody = @{
        username = "admin"
        password = "admin123"
    } | ConvertTo-Json

    $loginResponse = Invoke-WebRequest -Uri "$baseUrl/api/auth/login" `
        -Method POST `
        -ContentType "application/json" `
        -Body $loginBody `
        -UseBasicParsing

    $loginData = $loginResponse.Content | ConvertFrom-Json
    $token = $loginData.token
    
    if ($token) {
        Write-Host "OK Login successful" -ForegroundColor Green
    } else {
        throw "No token received"
    }
} catch {
    Write-Host "FAIL Login failed: $_" -ForegroundColor Red
    Write-Host "  Примечание: Убедитесь, что пользователь admin создан в БД" -ForegroundColor Gray
    $errors += "Login failed"
}

if (-not $token) {
    Write-Host ""
    Write-Host "Не удалось получить токен. Остальные тесты пропущены." -ForegroundColor Red
    Write-Host ""
    Write-Host "Создайте пользователя через seed скрипт:" -ForegroundColor Yellow
    Write-Host "  docker exec android-automation-backend npm run prisma:seed" -ForegroundColor Gray
    exit 1
}

$headers = @{ "Authorization" = "Bearer $token" }


Write-Host "[3/8] Получение списка устройств..." -ForegroundColor Yellow
try {
    $devices = Invoke-WebRequest -Uri "$baseUrl/api/devices" `
        -Headers $headers `
        -UseBasicParsing
    
    $devicesData = $devices.Content | ConvertFrom-Json
    Write-Host "OK Devices endpoint works (найдено устройств: $($devicesData.devices.Count))" -ForegroundColor Green
} catch {
    Write-Host "FAIL Get devices failed: $_" -ForegroundColor Red
    $errors += "Get devices failed"
}


Write-Host "[4/8] Получение списка прокси..." -ForegroundColor Yellow
try {
    $proxies = Invoke-WebRequest -Uri "$baseUrl/api/proxies" `
        -Headers $headers `
        -UseBasicParsing
    
    $proxiesData = $proxies.Content | ConvertFrom-Json
    Write-Host "OK Proxies endpoint works (найдено прокси: $($proxiesData.proxies.Count))" -ForegroundColor Green
} catch {
    Write-Host "FAIL Get proxies failed: $_" -ForegroundColor Red
    $errors += "Get proxies failed"
}


Write-Host "[5/8] Получение списка задач..." -ForegroundColor Yellow
try {
    $tasks = Invoke-WebRequest -Uri "$baseUrl/api/tasks" `
        -Headers $headers `
        -UseBasicParsing
    
    $tasksData = $tasks.Content | ConvertFrom-Json
    Write-Host "OK Tasks endpoint works (найдено задач: $($tasksData.tasks.Count))" -ForegroundColor Green
} catch {
    Write-Host "FAIL Get tasks failed: $_" -ForegroundColor Red
    $errors += "Get tasks failed"
}


Write-Host "[6/8] Получение распарсенных данных..." -ForegroundColor Yellow
try {
    $parsedData = Invoke-WebRequest -Uri "$baseUrl/api/parsed-data" `
        -Headers $headers `
        -UseBasicParsing
    
    $parsedDataData = $parsedData.Content | ConvertFrom-Json
    Write-Host "OK Parsed data endpoint works" -ForegroundColor Green
} catch {
    Write-Host "FAIL Get parsed data failed: $_" -ForegroundColor Red
    $errors += "Get parsed data failed"
}


Write-Host "[7/8] Проверка Bull Board..." -ForegroundColor Yellow
try {
    $bullBoard = Invoke-WebRequest -Uri "$baseUrl/admin/queues" `
        -UseBasicParsing
    
    if ($bullBoard.StatusCode -eq 200) {
        Write-Host "OK Bull Board доступен" -ForegroundColor Green
    } else {
        throw "Bull Board returned status $($bullBoard.StatusCode)"
    }
} catch {
    Write-Host "FAIL Bull Board check failed: $_" -ForegroundColor Red
    Write-Host "  Примечание: Bull Board доступен только в development режиме" -ForegroundColor Gray
    $errors += "Bull Board check failed"
}


Write-Host "[8/8] Проверка MinIO..." -ForegroundColor Yellow
try {
    $minio = Invoke-WebRequest -Uri "http://localhost:9000/minio/health/live" `
        -UseBasicParsing
    
    if ($minio.StatusCode -eq 200) {
        Write-Host "OK MinIO доступен" -ForegroundColor Green
    } else {
        throw "MinIO returned status $($minio.StatusCode)"
    }
} catch {
    Write-Host "FAIL MinIO check failed: $_" -ForegroundColor Red
    $errors += "MinIO check failed"
}


Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
if ($errors.Count -eq 0) {
    Write-Host "OK Все тесты пройдены успешно!" -ForegroundColor Green
} else {
    Write-Host "FAIL Найдено ошибок: $($errors.Count)" -ForegroundColor Red
    foreach ($error in $errors) {
        Write-Host "  - $error" -ForegroundColor Red
    }
}
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "Полезные ссылки:" -ForegroundColor Yellow
Write-Host "  Frontend: http://localhost:5173" -ForegroundColor Gray
Write-Host "  Backend API: http://localhost:3000" -ForegroundColor Gray
Write-Host "  Bull Board: http://localhost:3000/admin/queues" -ForegroundColor Gray
Write-Host "  MinIO Console: http://localhost:9001" -ForegroundColor Gray
Write-Host ""
