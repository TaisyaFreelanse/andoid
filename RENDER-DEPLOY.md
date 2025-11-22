# Деплой на Render.com

Это руководство описывает процесс деплоя проекта Android Browser Automation System на Render.com.

## Обзор архитектуры на Render

На Render проект будет развернут следующим образом:

- **Backend** - Web Service (Node.js/Fastify)
- **Frontend** - Static Site (React/Vite)
- **PostgreSQL** - Managed PostgreSQL Database
- **Redis** - Внешний сервис (Upstash, Redis Cloud и т.д.)
- **MinIO/S3** - Внешний S3-совместимый сервис (AWS S3, DigitalOcean Spaces, Backblaze B2)

## Предварительные требования

1. Аккаунт на [Render.com](https://render.com)
2. Внешний Redis сервис (рекомендуется [Upstash](https://upstash.com) - бесплатный план)
3. S3-совместимое хранилище (рекомендуется [DigitalOcean Spaces](https://www.digitalocean.com/products/spaces) или AWS S3)

## Шаг 1: Подготовка репозитория

1. Убедитесь, что файл `render.yaml` находится в корне репозитория
2. Закоммитьте все изменения в Git
3. Запушьте код в GitHub/GitLab/Bitbucket

## Шаг 2: Создание сервисов через Blueprint

### Вариант A: Использование render.yaml (Blueprint)

1. Войдите в [Render Dashboard](https://dashboard.render.com)
2. Нажмите "New +" → "Blueprint"
3. Подключите ваш репозиторий
4. Render автоматически обнаружит `render.yaml` и создаст все сервисы

### Вариант B: Ручное создание сервисов

Если предпочитаете создавать сервисы вручную, следуйте инструкциям ниже.

## Шаг 3: Создание PostgreSQL Database

1. В Render Dashboard: "New +" → "PostgreSQL"
2. Настройки:
   - **Name**: `android-automation-db`
   - **Database**: `android_automation`
   - **User**: `android_automation_user`
   - **Plan**: Starter (или выше для продакшена)
   - **Region**: Выберите ближайший регион
3. Нажмите "Create Database"
4. Сохраните `Connection String` - он понадобится для Backend

## Шаг 4: Создание Backend Service

1. В Render Dashboard: "New +" → "Web Service"
2. Подключите ваш репозиторий
3. Настройки:
   - **Name**: `android-automation-backend`
   - **Region**: Выберите тот же регион, что и база данных
   - **Branch**: `main` (или ваша основная ветка)
   - **Root Directory**: `backend`
   - **Environment**: `Node`
   - **Build Command**: 
     ```bash
     npm ci && npx prisma generate && npm run build
     ```
   - **Start Command**: 
     ```bash
     npx prisma migrate deploy && npm start
     ```
   - **Plan**: Starter (или выше)

4. **Environment Variables** (в разделе "Environment"):
   
   **Обязательные:**
   ```
   NODE_ENV=production
   PORT=3000
   HOST=0.0.0.0
   DATABASE_URL=<из PostgreSQL сервиса>
   JWT_SECRET=<сгенерируйте случайную строку минимум 32 символа>
   REFRESH_TOKEN_SECRET=<сгенерируйте случайную строку минимум 32 символа>
   JWT_EXPIRES_IN=24h
   REFRESH_TOKEN_EXPIRES_IN=7d
   LOG_LEVEL=info
   ```

   **Redis** (настройте после создания внешнего Redis):
   ```
   REDIS_HOST=<ваш-redis-host>
   REDIS_PORT=6379
   REDIS_PASSWORD=<ваш-redis-password>
   ```
   
   **MinIO/S3** (настройте после создания S3 хранилища):
   ```
   MINIO_ENDPOINT=<ваш-s3-endpoint>
   MINIO_PORT=443
   MINIO_USE_SSL=true
   MINIO_ACCESS_KEY=<ваш-access-key>
   MINIO_SECRET_KEY=<ваш-secret-key>
   MINIO_BUCKET_NAME=screenshots
   ```

   **Semrush API** (опционально):
   ```
   SEMRUSH_API_KEY=<ваш-api-key>
   ```

   **CORS** (будет обновлен автоматически после создания Frontend):
   ```
   CORS_ORIGIN=https://android-automation-frontend.onrender.com
   ```

5. Нажмите "Create Web Service"

## Шаг 5: Создание Frontend Service

1. В Render Dashboard: "New +" → "Static Site"
2. Подключите ваш репозиторий
3. Настройки:
   - **Name**: `android-automation-frontend`
   - **Branch**: `main`
   - **Root Directory**: `frontend`
   - **Build Command**: 
     ```bash
     npm ci && npm run build
     ```
   - **Publish Directory**: `dist`

4. **Environment Variables** (важно: эти переменные используются во время сборки):
   ```
   VITE_API_URL=https://android-automation-backend.onrender.com
   ```
   ⚠️ **Важно**: Замените `android-automation-backend` на реальное имя вашего Backend сервиса!

5. Нажмите "Create Static Site"

## Шаг 6: Настройка Redis

Render не предоставляет управляемый Redis, поэтому используйте внешний сервис:

### Вариант A: Upstash (рекомендуется)

1. Зарегистрируйтесь на [Upstash](https://upstash.com)
2. Создайте новый Redis database
3. Выберите регион, близкий к вашему Render региону
4. Скопируйте:
   - **Endpoint** → `REDIS_HOST`
   - **Port** → `REDIS_PORT` (обычно 6379 или 6380 для SSL)
   - **Password** → `REDIS_PASSWORD`
5. Обновите переменные окружения в Backend сервисе на Render

### Вариант B: Redis Cloud

1. Зарегистрируйтесь на [Redis Cloud](https://redis.com/try-free/)
2. Создайте новый database
3. Скопируйте connection details
4. Обновите переменные окружения в Backend сервисе

## Шаг 7: Настройка S3-совместимого хранилища

### Вариант A: DigitalOcean Spaces (рекомендуется)

1. Зарегистрируйтесь на [DigitalOcean](https://www.digitalocean.com)
2. Создайте новый Space:
   - **Name**: `android-automation-screenshots`
   - **Region**: Выберите тот же регион, что и Render
   - **CDN**: Включите (опционально)
3. Создайте Access Key:
   - Settings → Spaces Keys → Generate New Key
4. Обновите переменные окружения в Backend:
   ```
   MINIO_ENDPOINT=<region>.digitaloceanspaces.com
   MINIO_PORT=443
   MINIO_USE_SSL=true
   MINIO_ACCESS_KEY=<ваш-access-key>
   MINIO_SECRET_KEY=<ваш-secret-key>
   MINIO_BUCKET_NAME=screenshots
   ```

### Вариант B: AWS S3

1. Создайте S3 bucket в AWS Console
2. Создайте IAM user с правами на S3
3. Создайте Access Key для этого user
4. Обновите переменные окружения:
   ```
   MINIO_ENDPOINT=s3.amazonaws.com
   MINIO_PORT=443
   MINIO_USE_SSL=true
   MINIO_ACCESS_KEY=<aws-access-key>
   MINIO_SECRET_KEY=<aws-secret-key>
   MINIO_BUCKET_NAME=<ваш-bucket-name>
   ```

### Вариант C: Backblaze B2

1. Создайте B2 bucket
2. Создайте Application Key
3. Обновите переменные окружения:
   ```
   MINIO_ENDPOINT=<ваш-endpoint>.backblazeb2.com
   MINIO_PORT=443
   MINIO_USE_SSL=true
   MINIO_ACCESS_KEY=<application-key-id>
   MINIO_SECRET_KEY=<application-key>
   MINIO_BUCKET_NAME=<ваш-bucket-name>
   ```

## Шаг 8: Обновление CORS в Backend

После создания Frontend сервиса, обновите `CORS_ORIGIN` в Backend:

1. Откройте Backend сервис в Render Dashboard
2. Перейдите в "Environment"
3. Обновите `CORS_ORIGIN`:
   ```
   CORS_ORIGIN=https://android-automation-frontend.onrender.com
   ```
4. Сохраните изменения (Render автоматически перезапустит сервис)

## Шаг 9: Проверка деплоя

1. **Backend Health Check**:
   - Откройте: `https://android-automation-backend.onrender.com/health`
   - Должен вернуть: `{"status":"ok","timestamp":"..."}`

2. **Frontend**:
   - Откройте: `https://android-automation-frontend.onrender.com`
   - Должен загрузиться интерфейс

3. **Проверка подключения**:
   - Попробуйте войти в систему (admin/admin123)
   - Проверьте, что данные загружаются

## Шаг 10: Создание первого администратора

После успешного деплоя, создайте администратора через API:

```bash
curl -X POST https://android-automation-backend.onrender.com/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "username": "admin",
    "password": "your-secure-password",
    "role": "admin"
  }'
```

Или используйте Prisma Studio (только для разработки):

1. Подключитесь к базе данных через Render Dashboard
2. Используйте SQL Editor для создания пользователя

## Переменные окружения - Полный список

### Backend

```env
# Основные
NODE_ENV=production
PORT=3000
HOST=0.0.0.0

# База данных
DATABASE_URL=<из Render PostgreSQL>

# Redis
REDIS_HOST=<из Upstash/Redis Cloud>
REDIS_PORT=6379
REDIS_PASSWORD=<из Upstash/Redis Cloud>

# JWT
JWT_SECRET=<минимум 32 символа>
JWT_EXPIRES_IN=24h
REFRESH_TOKEN_SECRET=<минимум 32 символа>
REFRESH_TOKEN_EXPIRES_IN=7d

# MinIO/S3
MINIO_ENDPOINT=<s3-endpoint>
MINIO_PORT=443
MINIO_USE_SSL=true
MINIO_ACCESS_KEY=<access-key>
MINIO_SECRET_KEY=<secret-key>
MINIO_BUCKET_NAME=screenshots

# API Keys
SEMRUSH_API_KEY=<опционально>

# CORS
CORS_ORIGIN=https://android-automation-frontend.onrender.com

# Логирование
LOG_LEVEL=info
```

### Frontend

```env
# API URL (используется во время сборки)
VITE_API_URL=https://android-automation-backend.onrender.com
```

## Troubleshooting

### Backend не запускается

1. Проверьте логи: Render Dashboard → Backend Service → Logs
2. Убедитесь, что все переменные окружения установлены
3. Проверьте, что `DATABASE_URL` корректный
4. Убедитесь, что миграции Prisma выполнены успешно

### Frontend не подключается к Backend

1. Проверьте `VITE_API_URL` в переменных окружения Frontend
2. Убедитесь, что Backend доступен по указанному URL
3. Проверьте CORS настройки в Backend
4. Откройте DevTools → Network и проверьте ошибки

### WebSocket не работает

1. Render поддерживает WebSocket, но убедитесь, что используете правильный протокол (`wss://` для HTTPS)
2. Проверьте, что Backend правильно обрабатывает WebSocket соединения
3. Проверьте логи Backend для ошибок WebSocket

### Проблемы с базой данных

1. Проверьте, что PostgreSQL сервис запущен
2. Убедитесь, что `DATABASE_URL` правильный
3. Проверьте логи миграций Prisma

### Проблемы с Redis

1. Проверьте, что Redis сервис доступен из интернета
2. Убедитесь, что `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` правильные
3. Проверьте firewall настройки Redis сервиса

### Проблемы с S3/MinIO

1. Проверьте, что credentials правильные
2. Убедитесь, что bucket существует
3. Проверьте права доступа (IAM policy для AWS, или аналогичные для других провайдеров)

## Обновление кода

Render автоматически деплоит изменения при push в основную ветку (если включен Auto-Deploy).

Для ручного деплоя:
1. Render Dashboard → Ваш сервис → Manual Deploy
2. Выберите commit и нажмите "Deploy"

## Мониторинг

- **Logs**: Render Dashboard → Ваш сервис → Logs
- **Metrics**: Render Dashboard → Ваш сервис → Metrics
- **Health Checks**: Render автоматически проверяет `/health` endpoint

## Стоимость

Примерная стоимость на Render (Starter план):

- **Backend Web Service**: $7/месяц
- **Frontend Static Site**: Бесплатно
- **PostgreSQL Database**: $7/месяц
- **Итого**: ~$14/месяц

Плюс внешние сервисы:
- **Upstash Redis**: Бесплатный план доступен
- **DigitalOcean Spaces**: $5/месяц (250GB)

## Дополнительные ресурсы

- [Render Documentation](https://render.com/docs)
- [Render Environment Variables](https://render.com/docs/environment-variables)
- [Render PostgreSQL](https://render.com/docs/databases)
- [Upstash Redis](https://docs.upstash.com/redis)
- [DigitalOcean Spaces](https://docs.digitalocean.com/products/spaces/)

