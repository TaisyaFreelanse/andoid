# Fixes Cursor MCP user-render: "ECONNREFUSED 127.0.0.1:443"
# Cause: mcp.render.com resolves to 127.0.0.1 on your current DNS (common with ad-block / Pi-hole).
# This script adds a static hosts entry using the real A record (Cloudflare). Re-run if MCP breaks after months.

$ErrorActionPreference = "Stop"
$hostsPath = "$env:SystemRoot\System32\drivers\etc\hosts"
$marker = "# Cursor MCP Render DNS fix"

if (-not ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Host "Restarting elevated (UAC)..."
    Start-Process powershell.exe -Verb RunAs -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`""
    exit
}

$content = Get-Content -LiteralPath $hostsPath -Raw -ErrorAction SilentlyContinue
if ($content -and $content.Contains($marker)) {
    Write-Host "Hosts entry already present. Nothing to do."
    exit 0
}

$block = @"

$marker
216.24.57.7 mcp.render.com
"@

Add-Content -LiteralPath $hostsPath -Value $block -Encoding ascii
Write-Host "Done. Flushing DNS cache..."
ipconfig /flushdns | Out-Null
Write-Host "Try Cursor MCP again (or restart Cursor)."
