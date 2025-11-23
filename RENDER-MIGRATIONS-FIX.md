# Исправление проблемы с миграциями Prisma на Render

## Проблема

Ошибка:
```
The table `public.users` does not exist in the current database.
```

Это означает, что миграции Prisma не были выполнены при запуске сервера.

## Решение

### ✅ Автоматическое решение (уже применено)

Код обновлен для автоматического выполнения миграций при старте:
- Добавлен npm script `migrate:start` в `package.json`
- Обновлен `startCommand` в `render.yaml` для использования этого скрипта
- Сервер проверяет наличие таблиц при старте и выдает понятную ошибку, если их нет

**Просто закоммитьте и запушьте изменения - Render автоматически выполнит миграции при следующем деплое.**

### Вариант 1: Выполните миграции вручную через Render Shell (быстрое решение)

Если нужно исправить СЕЙЧАС, без ожидания деплоя:

1. В Render Dashboard откройте Backend Service
2. Перейдите в **Shell** (или используйте SSH)
3. Выполните:
   ```bash
   cd backend
   npx prisma migrate deploy --schema=./prisma/schema.prisma
   ```
4. Проверьте вывод - должны быть сообщения:
   ```
   Applying migration `20251119213000_init`
   The following migration(s) have been applied:
   - 20251119213000_init
   ```
5. Перезапустите сервис (Render Dashboard → Manual Deploy → Deploy latest commit)

### Вариант 2: Проверьте логи запуска

1. В Render Dashboard откройте Backend Service
2. Перейдите в **Logs**
3. Найдите строки с `Running Prisma migrations...` или `prisma migrate deploy`
4. Проверьте, есть ли ошибки при выполнении миграций
5. Если миграции не выполняются, используйте Вариант 1

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

