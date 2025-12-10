# PowerShell script to test domain checking API
$apiUrl = "http://localhost:3000/api/agent/test/domain-check"
$testDomains = @("google.com", "example.com", "github.com", "invalid-domain-12345.com")

Write-Host "🧪 Testing domain check API..." -ForegroundColor Cyan
Write-Host "API URL: $apiUrl`n" -ForegroundColor Gray

$body = @{
    domains = $testDomains
} | ConvertTo-Json

try {
    $response = Invoke-RestMethod -Uri $apiUrl -Method Post -Body $body -ContentType "application/json"
    
    Write-Host "✅ Test Results:`n" -ForegroundColor Green
    Write-Host "Summary:" -ForegroundColor Yellow
    Write-Host "  Total domains: $($response.summary.total)"
    Write-Host "  Valid: $($response.summary.valid)" -ForegroundColor Green
    Write-Host "  Invalid: $($response.summary.invalid)" -ForegroundColor Red
    Write-Host "  Exists: $($response.summary.exists)" -ForegroundColor Cyan
    Write-Host "  With metrics: $($response.summary.withMetrics)`n" -ForegroundColor Magenta
    
    Write-Host "Detailed Results:" -ForegroundColor Yellow
    $i = 1
    foreach ($result in $response.results) {
        Write-Host "`n$i. $($result.domain):" -ForegroundColor White
        Write-Host "   Valid: $($result.isValid)" -ForegroundColor $(if ($result.isValid) { "Green" } else { "Red" })
        Write-Host "   Exists: $($result.exists)" -ForegroundColor $(if ($result.exists) { "Green" } else { "Red" })
        Write-Host "   Source: $($result.source)" -ForegroundColor Gray
        if ($result.metrics) {
            Write-Host "   Metrics:" -ForegroundColor Cyan
            if ($result.metrics.domainRank) { Write-Host "     Domain Rank: $($result.metrics.domainRank)" }
            if ($result.metrics.organicKeywords) { Write-Host "     Organic Keywords: $($result.metrics.organicKeywords)" }
            if ($result.metrics.organicTraffic) { Write-Host "     Organic Traffic: $($result.metrics.organicTraffic)" }
            if ($result.metrics.backlinks) { Write-Host "     Backlinks: $($result.metrics.backlinks)" }
        }
        if ($result.error) {
            Write-Host "   Error: $($result.error)" -ForegroundColor Red
        }
        $i++
    }
    
    Write-Host "`n✅ Test completed successfully!" -ForegroundColor Green
} catch {
    Write-Host "❌ Test failed: $($_.Exception.Message)" -ForegroundColor Red
    if ($_.Exception.Response.StatusCode -eq 404 -or $_.Exception.Message -like "*ECONNREFUSED*") {
        Write-Host "`n💡 Make sure the backend server is running:" -ForegroundColor Yellow
        Write-Host "   cd backend && npm run dev" -ForegroundColor Gray
    }
}

