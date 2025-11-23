# Исправление проблемы с миграциями Prisma на Render

## Проблема

Ошибка:
```
The table `public.users` does not exist in the current database.
```

Это означает, что миграции Prisma не были выполнены при запуске сервера.

## Решение

### Вариант 1: Проверьте логи запуска (рекомендуется)

1. В Render Dashboard откройте Backend Service
2. Перейдите в **Logs**
3. Найдите строки с `prisma migrate deploy`
4. Проверьте, есть ли ошибки при выполнении миграций

### Вариант 2: Выполните миграции вручную через Render Shell

1. В Render Dashboard откройте Backend Service
2. Перейдите в **Shell** (или используйте SSH)
3. Выполните:
   ```bash
   cd backend
   npx prisma migrate deploy
   ```
4. Проверьте вывод - должны быть сообщения о применении миграций

### Вариант 3: Проверьте startCommand

Убедитесь, что в `render.yaml` команда запуска правильная:

```yaml
startCommand: npx prisma migrate deploy --schema=./prisma/schema.prisma && npm start
```

### Вариант 4: Создайте скрипт для миграций

Создайте файл `backend/scripts/migrate-and-start.sh`:

```bash
#!/bin/sh
set -e

echo "Running Prisma migrations..."
npx prisma migrate deploy

echo "Starting server..."
npm start
```

И обновите `render.yaml`:

```yaml
startCommand: sh scripts/migrate-and-start.sh
```

## Проверка

После выполнения миграций:

1. Проверьте health check:
   ```bash
   curl https://android-automation-backend.onrender.com/health
   ```
   Должен вернуть: `{"status":"ok","database":"connected"}`

2. Попробуйте залогиниться через API

3. Проверьте логи - не должно быть ошибок о несуществующих таблицах

## Частые проблемы

### "DATABASE_URL is not set"
- Убедитесь, что PostgreSQL создан в Blueprint
- Проверьте, что `DATABASE_URL` установлен в Environment Variables

### "Migration failed"
- Проверьте логи миграций
- Убедитесь, что схема Prisma корректна
- Проверьте права доступа к базе данных

### "Tables still don't exist after migration"
- Убедитесь, что миграции выполнены успешно (проверьте логи)
- Проверьте, что подключение идет к правильной базе данных
- Попробуйте выполнить миграции вручную через Shell

