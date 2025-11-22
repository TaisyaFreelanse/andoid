

set -e

echo "🚀 Initializing database..."

if [ -z "$DATABASE_URL" ]; then
    echo "❌ Error: DATABASE_URL is not set"
    echo "Please set DATABASE_URL in .env file"
    exit 1
fi


echo "📦 Generating Prisma Client..."
npx prisma generate

echo "📝 Creating migrations..."
npx prisma migrate dev --name init


echo "🌱 Seeding database..."
npm run prisma:seed

echo "✅ Database initialization completed!"
echo ""
echo "📝 Default credentials:"
echo "Admin:    admin / admin123"
echo "Operator: operator / operator123"
echo "Viewer:   viewer / viewer123"

