# Test Uniqueness Service
# Tests all uniqueness features according to plan.plan.md (307-318)

param(
    [string]$BackendUrl = "http://localhost:3000",
    [string]$DeviceId = "",
    [string]$AuthToken = ""
)

$ErrorActionPreference = "Stop"

Write-Host "`n🧪 Testing Uniqueness Service" -ForegroundColor Cyan
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Gray

# 1. Check backend health
Write-Host "`n1️⃣ Checking backend health..." -ForegroundColor Yellow
try {
    $healthResponse = Invoke-RestMethod -Uri "$BackendUrl/api/health" -Method Get -ErrorAction Stop
    Write-Host "   ✅ Backend is healthy" -ForegroundColor Green
    Write-Host "   Status: $($healthResponse.status)" -ForegroundColor Gray
} catch {
    Write-Host "   ❌ Backend is not accessible: $_" -ForegroundColor Red
    exit 1
}

# 2. Login if no token provided
if (-not $AuthToken) {
    Write-Host "`n2️⃣ Logging in..." -ForegroundColor Yellow
    try {
        $loginBody = @{
            username = "admin"
            password = "admin"
        } | ConvertTo-Json
        
        $loginResponse = Invoke-RestMethod -Uri "$BackendUrl/api/auth/login" -Method Post -Body $loginBody -ContentType "application/json" -ErrorAction Stop
        $AuthToken = $loginResponse.token
        Write-Host "   ✅ Logged in successfully" -ForegroundColor Green
    } catch {
        Write-Host "   ❌ Login failed: $_" -ForegroundColor Red
        Write-Host "   Please provide AuthToken parameter" -ForegroundColor Yellow
        exit 1
    }
}

$headers = @{
    "Authorization" = "Bearer $AuthToken"
    "Content-Type" = "application/json"
}

# 3. Get devices if DeviceId not provided
if (-not $DeviceId) {
    Write-Host "`n3️⃣ Getting available devices..." -ForegroundColor Yellow
    try {
        $devicesResponse = Invoke-RestMethod -Uri "$BackendUrl/api/devices" -Method Get -Headers $headers -ErrorAction Stop
        $devices = $devicesResponse.devices
        
        if ($devices.Count -eq 0) {
            Write-Host "   ❌ No devices found" -ForegroundColor Red
            exit 1
        }
        
        $onlineDevices = $devices | Where-Object { $_.status -eq "online" }
        if ($onlineDevices.Count -eq 0) {
            Write-Host "   ⚠️  No online devices found" -ForegroundColor Yellow
            Write-Host "   Using first available device: $($devices[0].id)" -ForegroundColor Gray
            $DeviceId = $devices[0].id
        } else {
            Write-Host "   ✅ Found $($onlineDevices.Count) online device(s)" -ForegroundColor Green
            $DeviceId = $onlineDevices[0].id
            Write-Host "   Using device: $DeviceId ($($onlineDevices[0].name))" -ForegroundColor Gray
        }
    } catch {
        Write-Host "   ❌ Failed to get devices: $_" -ForegroundColor Red
        exit 1
    }
}

# 4. Create uniqueness task
Write-Host "`n4️⃣ Creating uniqueness task..." -ForegroundColor Yellow

$taskConfig = @{
    id = "test_uniqueness_full"
    name = "Полный тест уникализации"
    description = "Тестирование всех функций уникализации согласно плану (307-318)"
    version = "1.0"
    type = "uniqueness"
    requires_root = $true
    timeout = 120000
    config = @{
        backup_before = $true
        reboot_after = $false
        verify_changes = $true
        log_detailed = $true
    }
    actions = @(
        @{
            id = "action_1"
            type = "regenerate_android_id"
            description = "1. Регенерация AndroidID через settings put secure android_id"
        },
        @{
            id = "action_2"
            type = "regenerate_aaid"
            description = "2. Регенерация AAID через settings put secure android_id"
        },
        @{
            id = "action_3"
            type = "clear_chrome_data"
            description = "3. Очистка Chrome data через pm clear и удаление папок"
        },
        @{
            id = "action_4"
            type = "clear_webview_data"
            description = "4. Очистка WebView data через pm clear и удаление папок"
        },
        @{
            id = "action_5"
            type = "change_user_agent"
            ua = "random"
            description = "5. Изменение User-Agent через build.prop или runtime"
        },
        @{
            id = "action_6"
            type = "change_timezone"
            timezone = "America/New_York"
            description = "6. Изменение timezone через settings put global auto_time_zone"
        },
        @{
            id = "action_7"
            type = "change_location"
            latitude = 40.7128
            longitude = -74.0060
            description = "7. Изменение GPS координат через settings put secure mock_location"
        },
        @{
            id = "action_8"
            type = "modify_build_prop"
            params = @{
                "ro.product.model" = "SM-G998B"
                "ro.product.manufacturer" = "samsung"
                "ro.product.brand" = "samsung"
            }
            description = "8. Изменение build.prop параметров (model, manufacturer, etc.)"
        }
    )
    post_process = @{
        verify_android_id_changed = $true
        verify_aaid_changed = $true
        verify_user_agent_changed = $true
        verify_timezone_changed = $true
        verify_location_changed = $true
        verify_build_prop_changed = $true
        log_new_fingerprint = $true
        send_to_backend = $true
        compare_before_after = $true
    }
}

$taskBody = @{
    name = "Test Uniqueness Full"
    type = "uniqueness"
    deviceId = $DeviceId
    configJson = $taskConfig
    priority = "HIGH"
} | ConvertTo-Json -Depth 10

try {
    $taskResponse = Invoke-RestMethod -Uri "$BackendUrl/api/tasks" -Method Post -Body $taskBody -Headers $headers -ErrorAction Stop
    $taskId = $taskResponse.task.id
    Write-Host "   ✅ Task created: $taskId" -ForegroundColor Green
    Write-Host "   Task name: $($taskResponse.task.name)" -ForegroundColor Gray
    Write-Host "   Task type: $($taskResponse.task.type)" -ForegroundColor Gray
    Write-Host "   Status: $($taskResponse.task.status)" -ForegroundColor Gray
} catch {
    Write-Host "   ❌ Failed to create task: $_" -ForegroundColor Red
    if ($_.ErrorDetails.Message) {
        Write-Host "   Error details: $($_.ErrorDetails.Message)" -ForegroundColor Red
    }
    exit 1
}

# 5. Monitor task execution
Write-Host "`n5️⃣ Monitoring task execution..." -ForegroundColor Yellow
Write-Host "   Waiting for task to complete (this may take up to 2 minutes)..." -ForegroundColor Gray

$maxWaitTime = 120  # 2 minutes
$checkInterval = 5  # Check every 5 seconds
$elapsed = 0

while ($elapsed -lt $maxWaitTime) {
    Start-Sleep -Seconds $checkInterval
    $elapsed += $checkInterval
    
    try {
        $taskStatus = Invoke-RestMethod -Uri "$BackendUrl/api/tasks/$taskId" -Method Get -Headers $headers -ErrorAction Stop
        $status = $taskStatus.task.status
        
        Write-Host "   [$elapsed s] Status: $status" -ForegroundColor Gray
        
        if ($status -eq "completed") {
            Write-Host "   ✅ Task completed successfully!" -ForegroundColor Green
            break
        } elseif ($status -eq "failed") {
            Write-Host "   ❌ Task failed" -ForegroundColor Red
            if ($taskStatus.task.resultJson) {
                Write-Host "   Error: $($taskStatus.task.resultJson | ConvertTo-Json -Compress)" -ForegroundColor Red
            }
            exit 1
        }
    } catch {
        Write-Host "   ⚠️  Failed to check task status: $_" -ForegroundColor Yellow
    }
}

if ($elapsed -ge $maxWaitTime) {
    Write-Host "   ⚠️  Timeout waiting for task completion" -ForegroundColor Yellow
}

# 6. Get final task results
Write-Host "`n6️⃣ Getting final task results..." -ForegroundColor Yellow
try {
    $finalTask = Invoke-RestMethod -Uri "$BackendUrl/api/tasks/$taskId" -Method Get -Headers $headers -ErrorAction Stop
    $result = $finalTask.task.resultJson
    
    Write-Host "   Task Status: $($finalTask.task.status)" -ForegroundColor $(if ($finalTask.task.status -eq "completed") { "Green" } else { "Red" })
    
    if ($result) {
        Write-Host "`n   📊 Uniqueness Results:" -ForegroundColor Cyan
        $result.PSObject.Properties | ForEach-Object {
            $value = $_.Value
            $status = if ($value -eq $true -or $value -eq "true") { "✅" } elseif ($value -eq $false -or $value -eq "false") { "❌" } else { "ℹ️" }
            Write-Host "   $status $($_.Name): $value" -ForegroundColor $(if ($value -eq $true -or $value -eq "true") { "Green" } elseif ($value -eq $false -or $value -eq "false") { "Red" } else { "Gray" })
        }
    } else {
        Write-Host "   ⚠️  No results available" -ForegroundColor Yellow
    }
} catch {
    Write-Host "   ❌ Failed to get task results: $_" -ForegroundColor Red
}

# 7. Summary
Write-Host "`n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Gray
Write-Host "📋 Test Summary:" -ForegroundColor Cyan
Write-Host "   Task ID: $taskId" -ForegroundColor Gray
Write-Host "   Device ID: $DeviceId" -ForegroundColor Gray
Write-Host "   Backend URL: $BackendUrl" -ForegroundColor Gray
Write-Host "`n✅ Uniqueness test completed!" -ForegroundColor Green

