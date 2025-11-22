

$ErrorActionPreference = "Stop"

Write-Host "🚀 Initializing database..." -ForegroundColor Cyan

if (-not $env:DATABASE_URL) {
    Write-Host "❌ Error: DATABASE_URL is not set" -ForegroundColor Red
    Write-Host "Please set DATABASE_URL in .env file" -ForegroundColor Yellow
    exit 1
}


Write-Host "📦 Generating Prisma Client..." -ForegroundColor Cyan
npx prisma generate


Write-Host "📝 Creating migrations..." -ForegroundColor Cyan
npx prisma migrate dev --name init


Write-Host "🌱 Seeding database..." -ForegroundColor Cyan
npm run prisma:seed

Write-Host "✅ Database initialization completed!" -ForegroundColor Green
Write-Host ""
Write-Host "📝 Default credentials:" -ForegroundColor Yellow
Write-Host "Admin:    admin / admin123"
Write-Host "Operator: operator / operator123"
Write-Host "Viewer:   viewer / viewer123"

