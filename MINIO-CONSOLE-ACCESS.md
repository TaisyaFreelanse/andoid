# Доступ к MinIO Console на Render

## 🔧 Настройка Console

MinIO Console настроена для работы через основной URL Render. После завершения деплоя попробуйте:

### Вариант 1: Через основной URL (после деплоя)
- **URL:** `https://android-automation-minio.onrender.com`
- **Username:** `minioadmin`
- **Password:** `minioadmin123`

### Вариант 2: Использовать MinIO Client (mc) - Рекомендуется

MinIO Client - это лучший способ управления MinIO на Render:

#### Установка mc:

**Windows:**
```powershell
# Через Chocolatey
choco install minio-client

# Или скачать с https://dl.min.io/client/mc/release/windows-amd64/mc.exe
```

**Linux/Mac:**
```bash
# Linux
wget https://dl.min.io/client/mc/release/linux-amd64/mc
chmod +x mc
sudo mv mc /usr/local/bin/

# Mac
brew install minio-client
```

#### Подключение к MinIO:

```bash
# Настройка alias
mc alias set myminio https://android-automation-minio.onrender.com minioadmin minioadmin123

# Проверка подключения
mc admin info myminio

# Просмотр bucket
mc ls myminio/screenshots

# Создание bucket (если нужно)
mc mb myminio/new-bucket

# Загрузка файла
mc cp local-file.png myminio/screenshots/

# Скачивание файла
mc cp myminio/screenshots/file.png ./

# Просмотр содержимого bucket
mc ls myminio/screenshots --recursive

# Удаление файла
mc rm myminio/screenshots/file.png
```

### Вариант 3: Использовать MinIO Console локально

Установите MinIO Console локально и подключитесь к удаленному серверу:

```bash
# Установка MinIO Console
# Windows: скачать с https://github.com/minio/console/releases
# Linux/Mac: через package manager

# Запуск Console
minio console

# В браузере откройте http://localhost:9001
# Подключитесь к: https://android-automation-minio.onrender.com
# Credentials: minioadmin / minioadmin123
```

### Вариант 4: Использовать через Backend API

Backend уже настроен для работы с MinIO. Все операции можно выполнять через backend API:

- Загрузка файлов: `POST /api/artifacts/upload`
- Получение файлов: `GET /api/artifacts/:id`
- Список файлов: `GET /api/artifacts`

## 📊 Текущая конфигурация

- **MinIO API:** `https://android-automation-minio.onrender.com` (порт 9000)
- **MinIO Console:** Настраивается через `MINIO_BROWSER_REDIRECT_URL`
- **Access Key:** `minioadmin`
- **Secret Key:** `minioadmin123`
- **Bucket:** `screenshots` (уже создан)

## ⚠️ Если Console все еще не работает

На Render иногда сложно настроить Console из-за особенностей проксирования. В этом случае:

1. **Используйте MinIO Client (mc)** - самый надежный способ
2. **Используйте Backend API** - все операции доступны через backend
3. **Используйте MinIO SDK** - для программного доступа

## 🔒 Безопасность

После настройки измените пароли:
- `MINIO_ROOT_PASSWORD` в Render Dashboard
- `MINIO_SECRET_KEY` в backend сервисе

