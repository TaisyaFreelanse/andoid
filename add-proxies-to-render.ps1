# Add 5 US mobile proxies to Render backend database
# Usage: .\add-proxies-to-render.ps1

param(
    [string]$BackendUrl = "https://android-automation-backend.onrender.com",
    [string]$Username = "admin",
    [string]$Password = "admin123"
)

$ErrorActionPreference = "Stop"

Write-Host "`n🔧 Adding Proxies to Render Backend" -ForegroundColor Cyan
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Gray

# 1. Login
Write-Host "`n1️⃣ Logging in..." -ForegroundColor Yellow
try {
    $loginBody = @{
        username = $Username
        password = $Password
    } | ConvertTo-Json
    
    $loginResponse = Invoke-RestMethod -Uri "$BackendUrl/api/auth/login" -Method Post -Body $loginBody -ContentType "application/json" -ErrorAction Stop
    $authToken = $loginResponse.token
    Write-Host "   ✅ Logged in successfully" -ForegroundColor Green
} catch {
    Write-Host "   ❌ Login failed: $_" -ForegroundColor Red
    if ($_.ErrorDetails.Message) {
        Write-Host "   Error details: $($_.ErrorDetails.Message)" -ForegroundColor Red
    }
    exit 1
}

$headers = @{
    "Authorization" = "Bearer $authToken"
    "Content-Type" = "application/json"
}

# 2. Define proxies
$proxies = @(
    @{
        host = "x398.fxdx.in"
        port = 18577
        username = "bmusproxy023054"
        password = "n6zpj7v773wa"
        type = "socks5"
        country = "US"
        timezone = "America/New_York"
        description = "NY - Mobile proxy with auto IP rotation every 10 minutes"
    },
    @{
        host = "x356.fxdx.in"
        port = 14517
        username = "bmusproxy294603"
        password = "pkrygbfbby73"
        type = "socks5"
        country = "US"
        timezone = "America/Los_Angeles"
        description = "CA - Mobile proxy with auto IP rotation every 10 minutes"
    },
    @{
        host = "x468.fxdx.in"
        port = 13873
        username = "bmusproxy054045"
        password = "s35cbq2gnmvv"
        type = "socks5"
        country = "US"
        timezone = "America/Chicago"
        description = "TX - Mobile proxy with auto IP rotation every 10 minutes"
    },
    @{
        host = "x343.fxdx.in"
        port = 14653
        username = "bmusproxy233723"
        password = "dmsbx95cn5sr"
        type = "socks5"
        country = "US"
        timezone = "America/New_York"
        description = "FL - Mobile proxy with auto IP rotation every 10 minutes"
    },
    @{
        host = "x335.fxdx.in"
        port = 14613
        username = "bmusproxy285133"
        password = "Rc4uFGanCj0e5"
        type = "socks5"
        country = "US"
        timezone = "America/Chicago"
        description = "IL - Mobile proxy with auto IP rotation every 10 minutes"
    }
)

# 3. Check existing proxies
Write-Host "`n2️⃣ Checking existing proxies..." -ForegroundColor Yellow
try {
    $existingProxies = Invoke-RestMethod -Uri "$BackendUrl/api/proxies" -Method Get -Headers $headers -ErrorAction Stop
    $existingCount = $existingProxies.proxies.Count
    Write-Host "   Found $existingCount existing proxy(ies)" -ForegroundColor Gray
    
    # Check if any of our proxies already exist
    $existingHosts = $existingProxies.proxies | ForEach-Object { "$($_.host):$($_.port)" }
} catch {
    Write-Host "   ⚠️  Could not check existing proxies: $_" -ForegroundColor Yellow
    $existingHosts = @()
}

# 4. Add proxies
Write-Host "`n3️⃣ Adding proxies..." -ForegroundColor Yellow
$added = 0
$skipped = 0
$failed = 0

foreach ($proxy in $proxies) {
    $proxyKey = "$($proxy.host):$($proxy.port)"
    
    # Check if already exists
    if ($existingHosts -contains $proxyKey) {
        Write-Host "   ⏭️  Skipping $proxyKey (already exists)" -ForegroundColor Yellow
        $skipped++
        continue
    }
    
    try {
        $proxyBody = @{
            host = $proxy.host
            port = $proxy.port
            username = $proxy.username
            password = $proxy.password
            type = $proxy.type
            country = $proxy.country
            timezone = $proxy.timezone
        } | ConvertTo-Json
        
        $response = Invoke-RestMethod -Uri "$BackendUrl/api/proxies" -Method Post -Body $proxyBody -Headers $headers -ErrorAction Stop
        Write-Host "   ✅ Added: $proxyKey ($($proxy.description))" -ForegroundColor Green
        Write-Host "      ID: $($response.proxy.id)" -ForegroundColor Gray
        $added++
    } catch {
        Write-Host "   ❌ Failed to add $proxyKey : $_" -ForegroundColor Red
        if ($_.ErrorDetails.Message) {
            Write-Host "      Error: $($_.ErrorDetails.Message)" -ForegroundColor Red
        }
        $failed++
    }
}

# 5. Summary
Write-Host "`n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Gray
Write-Host "📊 Summary:" -ForegroundColor Cyan
Write-Host "   ✅ Added: $added" -ForegroundColor Green
Write-Host "   ⏭️  Skipped: $skipped" -ForegroundColor Yellow
Write-Host "   ❌ Failed: $failed" -ForegroundColor $(if ($failed -gt 0) { "Red" } else { "Gray" })
Write-Host "`n✅ Done!" -ForegroundColor Green

