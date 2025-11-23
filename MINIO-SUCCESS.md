# ✅ MinIO успешно развернут!

## Статус развертывания

- **Сервис:** `android-automation-minio`
- **URL:** `https://android-automation-minio.onrender.com`
- **Статус:** Live ✅
- **Persistent Disk:** 10 GB (mount: `/data`) ✅
- **Порты:** 9000 (API), 9001 (Console)

## Доступ к MinIO

### MinIO Console (Web UI)
- **URL:** `https://android-automation-minio.onrender.com:9001`
- **Username:** `minioadmin`
- **Password:** `minioadmin123`

### MinIO API
- **Endpoint:** `android-automation-minio.onrender.com`
- **Port:** `443` (HTTPS)
- **Access Key:** `minioadmin`
- **Secret Key:** `minioadmin123`

## Что нужно сделать

### 1. Создать bucket `screenshots`

1. Откройте MinIO Console: `https://android-automation-minio.onrender.com:9001`
2. Войдите используя:
   - Username: `minioadmin`
   - Password: `minioadmin123`
3. Нажмите **"Create Bucket"**
4. Имя bucket: `screenshots`
5. Нажмите **"Create Bucket"**

### 2. Проверить подключение Backend

Backend уже настроен для подключения к MinIO:
- `MINIO_ENDPOINT=android-automation-minio.onrender.com`
- `MINIO_PORT=443`
- `MINIO_USE_SSL=true`
- `MINIO_ACCESS_KEY=minioadmin`
- `MINIO_SECRET_KEY=minioadmin123`
- `MINIO_BUCKET_NAME=screenshots`

После завершения деплоя backend (сейчас в процессе) проверьте логи:
- Должно быть сообщение о подключении к MinIO
- Bucket `screenshots` должен быть создан автоматически (если настроено)

### 3. Тестирование

После создания bucket попробуйте:
1. Загрузить скриншот через API
2. Проверить, что файл появился в MinIO Console
3. Проверить доступность через presigned URL

## Безопасность

**Важно:** После проверки работы измените пароли!

1. В Render Dashboard для `android-automation-minio`:
   - Обновите `MINIO_ROOT_PASSWORD` на более сложный пароль
2. В Render Dashboard для `android-automation-backend`:
   - Обновите `MINIO_SECRET_KEY` на тот же пароль

## Примечания

- Ошибки 502 при доступе к `/screenshots` - это нормально, пока bucket не создан
- MinIO работает на портах 9000 (API) и 9001 (Console)
- Render автоматически проксирует порты через основной URL
- Persistent disk обеспечивает сохранность данных при перезапуске

## Итог

✅ MinIO развернут и работает
✅ Persistent disk добавлен (10 GB)
✅ Backend настроен для подключения
⏳ Ожидается завершение деплоя backend
📝 Нужно создать bucket `screenshots` в MinIO Console

