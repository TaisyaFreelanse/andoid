# Быстрый старт: Деплой на Render

Это краткая инструкция для быстрого деплоя. Подробная документация: [RENDER-DEPLOY.md](./RENDER-DEPLOY.md)

## Шаги деплоя

### 1. Подготовка внешних сервисов

**Redis (Upstash - бесплатно):**
1. Зарегистрируйтесь на [upstash.com](https://upstash.com)
2. Создайте Redis database
3. Скопируйте: Endpoint, Port, Password

**S3 Storage (DigitalOcean Spaces - $5/месяц):**
1. Зарегистрируйтесь на [digitalocean.com](https://www.digitalocean.com)
2. Создайте Space в том же регионе, что и Render
3. Создайте Access Key
4. Скопируйте: Endpoint, Access Key, Secret Key

### 2. Деплой через Blueprint

1. Войдите в [Render Dashboard](https://dashboard.render.com)
2. "New +" → "Blueprint"
3. Подключите ваш GitHub/GitLab репозиторий
4. Render автоматически создаст все сервисы из `render.yaml`

### 3. Настройка переменных окружения

После создания сервисов, обновите переменные в **Backend Service**:

```
REDIS_HOST=<ваш-upstash-endpoint>
REDIS_PORT=6379
REDIS_PASSWORD=<ваш-upstash-password>
MINIO_ENDPOINT=<ваш-spaces-endpoint>.digitaloceanspaces.com
MINIO_ACCESS_KEY=<ваш-access-key>
MINIO_SECRET_KEY=<ваш-secret-key>
SEMRUSH_API_KEY=<опционально>
```

В **Frontend Service** установите:

```
VITE_API_URL=https://android-automation-backend.onrender.com
```

⚠️ **Важно:** Замените `android-automation-backend` на реальное имя вашего Backend сервиса!

### 4. Проверка

1. Backend: `https://your-backend.onrender.com/health`
2. Frontend: `https://your-frontend.onrender.com`

## Стоимость

- Backend: $7/месяц (Starter)
- Frontend: Бесплатно
- PostgreSQL: $7/месяц (Starter)
- Redis: Бесплатно (Upstash free tier)
- S3: $5/месяц (DigitalOcean Spaces)

**Итого:** ~$19/месяц

## Проблемы?

См. подробную документацию: [RENDER-DEPLOY.md](./RENDER-DEPLOY.md)

