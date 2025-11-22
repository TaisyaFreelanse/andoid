#!/bin/sh
# Скрипт для выполнения миграций Prisma на Render
# Этот скрипт используется в startCommand для Render

set -e

echo "Waiting for PostgreSQL to be ready..."
until pg_isready -h $DATABASE_HOST -p $DATABASE_PORT -U $DATABASE_USER; do
  echo "PostgreSQL is unavailable - sleeping"
  sleep 2
done

echo "PostgreSQL is up - executing migrations"
npx prisma migrate deploy

echo "Migrations completed successfully"

