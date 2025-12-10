# PowerShell script to test Render backend domain checking
param(
    [string]$BackendUrl = "http://localhost:3000"
)

Write-Host "🧪 Testing Render Backend Domain Checking API..." -ForegroundColor Cyan
Write-Host "Backend URL: $BackendUrl`n" -ForegroundColor Gray

# Test 1: Health Check
Write-Host "1️⃣ Testing Health Endpoint..." -ForegroundColor Yellow
try {
    $healthResponse = Invoke-RestMethod -Uri "$BackendUrl/health" -Method Get -TimeoutSec 10
    Write-Host "   ✅ Health check passed" -ForegroundColor Green
    Write-Host "   Status: $($healthResponse.status)" -ForegroundColor Gray
    Write-Host "   Database: $($healthResponse.database)`n" -ForegroundColor Gray
} catch {
    Write-Host "   ❌ Health check failed: $($_.Exception.Message)" -ForegroundColor Red
    Write-Host "   Make sure backend is running at $BackendUrl`n" -ForegroundColor Yellow
    exit 1
}

# Test 2: Domain Check API
Write-Host "2️⃣ Testing Domain Check API..." -ForegroundColor Yellow
$testDomains = @("google.com", "example.com", "github.com", "invalid-domain-12345-test.com")

$body = @{
    domains = $testDomains
} | ConvertTo-Json

try {
    Write-Host "   Sending request with domains: $($testDomains -join ', ')" -ForegroundColor Gray
    $response = Invoke-RestMethod -Uri "$BackendUrl/api/agent/test/domain-check" -Method Post -Body $body -ContentType "application/json" -TimeoutSec 30
    
    Write-Host "   ✅ Domain check API responded`n" -ForegroundColor Green
    
    Write-Host "   Summary:" -ForegroundColor Cyan
    Write-Host "     Total domains: $($response.summary.total)" -ForegroundColor White
    Write-Host "     Valid: $($response.summary.valid)" -ForegroundColor Green
    Write-Host "     Invalid: $($response.summary.invalid)" -ForegroundColor Red
    Write-Host "     Exists: $($response.summary.exists)" -ForegroundColor Cyan
    Write-Host "     With metrics: $($response.summary.withMetrics)`n" -ForegroundColor Magenta
    
    Write-Host "   Detailed Results:" -ForegroundColor Cyan
    $i = 1
    foreach ($result in $response.results) {
        $statusColor = if ($result.isValid) { "Green" } else { "Red" }
        Write-Host "   $i. $($result.domain):" -ForegroundColor White
        Write-Host "      Valid: $($result.isValid)" -ForegroundColor $statusColor
        Write-Host "      Exists: $($result.exists)" -ForegroundColor $(if ($result.exists) { "Green" } else { "Red" })
        Write-Host "      Source: $($result.source)" -ForegroundColor Gray
        
        if ($result.metrics) {
            Write-Host "      Metrics:" -ForegroundColor Cyan
            if ($result.metrics.domainRank) { Write-Host "        Domain Rank: $($result.metrics.domainRank)" -ForegroundColor White }
            if ($result.metrics.organicKeywords) { Write-Host "        Organic Keywords: $($result.metrics.organicKeywords)" -ForegroundColor White }
            if ($result.metrics.organicTraffic) { Write-Host "        Organic Traffic: $($result.metrics.organicTraffic)" -ForegroundColor White }
            if ($result.metrics.backlinks) { Write-Host "        Backlinks: $($result.metrics.backlinks)" -ForegroundColor White }
        }
        
        if ($result.error) {
            Write-Host "      ⚠️ Error: $($result.error)" -ForegroundColor Yellow
        }
        $i++
    }
    
    Write-Host "`n   ✅ Domain check test completed!" -ForegroundColor Green
    
    # Validate results
    if ($response.summary.withMetrics -gt 0) {
        Write-Host "`n   🎉 SUCCESS: API is working correctly! Found $($response.summary.withMetrics) domains with metrics." -ForegroundColor Green
    } else {
        Write-Host "`n   ⚠️ WARNING: No domains with metrics found. Check RapidAPI configuration." -ForegroundColor Yellow
    }
    
} catch {
    Write-Host "   ❌ Domain check failed: $($_.Exception.Message)" -ForegroundColor Red
    if ($_.Exception.Response) {
        $statusCode = $_.Exception.Response.StatusCode.value__
        Write-Host "   Status Code: $statusCode" -ForegroundColor Red
        try {
            $errorStream = $_.Exception.Response.GetResponseStream()
            $reader = New-Object System.IO.StreamReader($errorStream)
            $errorBody = $reader.ReadToEnd()
            Write-Host "   Error Body: $errorBody" -ForegroundColor Red
        } catch {
            Write-Host "   Could not read error body" -ForegroundColor Gray
        }
    }
    exit 1
}

Write-Host "`n✅ All tests completed!" -ForegroundColor Green

