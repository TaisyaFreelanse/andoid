# Конфигурация MinIO на Render

## ✅ Что уже сделано:

1. **MinIO сервис создан:**
   - Имя: `android-automation-minio`
   - URL: `https://android-automation-minio.onrender.com`
   - План: Starter
   - Environment Variables добавлены:
     - `MINIO_ROOT_USER=minioadmin`
     - `MINIO_ROOT_PASSWORD=minioadmin123`

2. **Backend переменные окружения обновлены:**
   - `MINIO_ENDPOINT=android-automation-minio.onrender.com`
   - `MINIO_PORT=443`
   - `MINIO_USE_SSL=true`
   - `MINIO_ACCESS_KEY=minioadmin`
   - `MINIO_SECRET_KEY=minioadmin123`
   - `MINIO_BUCKET_NAME=screenshots`

## ⚠️ Что нужно сделать вручную:

### 1. Обновить Dockerfile Path и Root Directory

В Render Dashboard для сервиса `android-automation-minio`:

1. Перейдите в **Settings**
2. Обновите:
   - **Root Directory:** `minio`
   - **Dockerfile Path:** `./minio/Dockerfile`

Или закоммитьте изменения в Git и они применятся автоматически.

### 2. Добавить Persistent Disk

**Важно:** MinIO требует persistent disk для хранения данных!

1. В Render Dashboard для `android-automation-minio`
2. Перейдите в **Settings** → **Disks**
3. Нажмите **"Add Disk"**
4. Укажите:
   - **Mount Path:** `/data`
   - **Size:** `10 GB` (минимум для Starter плана)

### 3. Проверить работу

После добавления disk и завершения деплоя:

1. Откройте MinIO Console: `https://android-automation-minio.onrender.com:9001`
2. Войдите используя:
   - Username: `minioadmin`
   - Password: `minioadmin123`
3. Создайте bucket `screenshots`
4. Проверьте логи backend - должно быть сообщение о подключении к MinIO

## 🔒 Безопасность

**Важно:** После проверки работы измените пароли!

1. В Render Dashboard для `android-automation-minio`:
   - Обновите `MINIO_ROOT_PASSWORD` на более сложный пароль
2. В Render Dashboard для `android-automation-backend`:
   - Обновите `MINIO_SECRET_KEY` на тот же пароль

## 📝 Примечания

- MinIO API доступен на порту 9000 (через Render proxy)
- MinIO Console доступна на порту 9001 (через Render proxy)
- Для HTTPS используйте порт 443
- Persistent disk обязателен - без него данные не сохранятся при перезапуске

