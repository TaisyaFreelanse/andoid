# Настройка MinIO на Render

## Автоматическое создание через MCP

Я могу создать MinIO сервис автоматически, но для этого нужно:

1. **Закоммитить MinIO Dockerfile в Git:**
   ```bash
   git add minio/Dockerfile minio/.dockerignore
   git commit -m "Add MinIO Docker configuration"
   git push origin main
   ```

2. **После этого я создам сервис через MCP**

## Ручное создание через Render Dashboard

### Шаг 1: Создать Web Service

1. Перейдите в [Render Dashboard](https://dashboard.render.com)
2. Нажмите **"New +"** → **"Web Service"**
3. Подключите ваш GitHub репозиторий: `TaisyaFreelanse/andoid`
4. Настройте:
   - **Name:** `android-automation-minio`
   - **Environment:** `Docker`
   - **Dockerfile Path:** `./minio/Dockerfile`
   - **Root Directory:** `minio`
   - **Region:** `Oregon`
   - **Branch:** `main`
   - **Plan:** `Starter` (минимум для persistent disk)

### Шаг 2: Настроить Environment Variables

В разделе **Environment Variables** добавьте:

- **MINIO_ROOT_USER:** `minioadmin` (или сгенерируйте свой)
- **MINIO_ROOT_PASSWORD:** `minioadmin123` (или сгенерируйте свой, минимум 8 символов)

### Шаг 3: Добавить Persistent Disk

1. После создания сервиса перейдите в **Settings**
2. В разделе **Disks** нажмите **"Add Disk"**
3. Укажите:
   - **Mount Path:** `/data`
   - **Size:** `10 GB` (минимум для Starter плана)

### Шаг 4: Обновить переменные окружения Backend

После создания MinIO сервиса, обновите переменные окружения в `android-automation-backend`:

- **MINIO_ENDPOINT:** `android-automation-minio.onrender.com` (или внутренний адрес)
- **MINIO_PORT:** `443` (для HTTPS) или `80` (для HTTP)
- **MINIO_USE_SSL:** `true` (если используете HTTPS)
- **MINIO_ACCESS_KEY:** значение из `MINIO_ROOT_USER`
- **MINIO_SECRET_KEY:** значение из `MINIO_ROOT_PASSWORD`
- **MINIO_BUCKET_NAME:** `screenshots`

## Проверка работы

1. Откройте MinIO Console: `https://android-automation-minio.onrender.com:9001`
2. Войдите используя `MINIO_ROOT_USER` и `MINIO_ROOT_PASSWORD`
3. Создайте bucket `screenshots`
4. Проверьте подключение из backend через логи

## Примечания

- MinIO на Render требует **Starter план** или выше (для persistent disk)
- Free план не поддерживает persistent disks
- Минимальный размер диска: 10 GB
- MinIO Console доступна на порту 9001
- MinIO API доступен на порту 9000

