#!/bin/sh
set -e

echo "========================================="
echo "Starting migration and server startup..."
echo "========================================="

# Проверяем, что DATABASE_URL установлен
if [ -z "$DATABASE_URL" ]; then
  echo "ERROR: DATABASE_URL is not set!"
  exit 1
fi

echo "DATABASE_URL is set (length: ${#DATABASE_URL})"

# Выполняем миграции
echo "Running Prisma migrations..."
npx prisma migrate deploy --schema=./prisma/schema.prisma

if [ $? -eq 0 ]; then
  echo "✓ Migrations completed successfully"
else
  echo "✗ Migration failed!"
  exit 1
fi

# Запускаем сервер
echo "Starting server..."
npm start

