Android Browser Automation System

Система автоматизации браузеров на Android устройствах для серфинга сайтов, парсинга рекламных баннеров и проверки доменов через Semrush API.

 Структура проекта

.
├── docs/                           Документация проекта
│   ├── architecture.md             Архитектура системы (Markdown)
│   ├── architecture.html           Архитектура (HTML для конвертации в PDF)
│   ├── erd.md                      ERD диаграмма базы данных
│   ├── openapi.yaml                OpenAPI спецификация (Swagger)
│   ├── security-plan.md            План безопасности
│   └── implementation-checklist.md  # Checklist для реализации
├── plan.plan.md            План разработки (все этапы)
└── README.md               Этот файл
Этап 1 Проектирование 

Этап 1 завершен. Созданы все необходимые артефакты для начала разработки.
Созданные документы
1. **Архитектура системы** (`docs/architecture.md`)
   - Описание компонентов системы
   - Схемы взаимодействия
   - Потоки данных
   - Технические детали

2. **ERD диаграмма** (`docs/erd.md`)
   - Схема базы данных
   - Описание всех таблиц
   - Связи между таблицами
   - Индексы и ограничения

3. **OpenAPI спецификация** (`docs/openapi.yaml`)
   - Полная спецификация REST API
   - Все endpoints с описаниями
   - Схемы данных
   - Примеры запросов/ответов

4. **План безопасности** (`docs/security-plan.md`)
   - Управление секретами
   - Аутентификация и авторизация
   - Защита данных
   - Audit логирование

5. **Checklist для реализации** (`docs/implementation-checklist.md`)
   - Детальный чеклист всех компонентов
   - Разбивка по этапам разработки

### Просмотр OpenAPI спецификации

Для просмотра интерактивной документации API:

1. Установите Swagger UI:
   ```bash
   npm install -g swagger-ui-serve
   ```

2. Запустите:
   ```bash
   swagger-ui-serve docs/openapi.yaml
   ```

Или используйте онлайн редактор: https://editor.swagger.io/ (импортируйте `docs/openapi.yaml`)

 Следующие этапы

- **Этап 2:** Backend + Очередь + Минимальный UI (20 дней)
- **Этап 3:** Android Agent (APK) (20 дней)
- **Этап 4:** Хранение и экспорт, артефакты (10 дней)
- **Этап 5:** Интеграция, тестирование, передача (10 дней)

Подробности в файле `plan.plan.md`.

 Технологический стек

 Backend
- Node.js + TypeScript
- Fastify (веб-фреймворк)
- PostgreSQL (база данных)
- Redis + Bull (очередь задач)
- MinIO/S3 (хранение файлов)
- Prisma (ORM)

 Android Agent
- Kotlin
- Android SDK
- Root доступ для уникализации

 Web UI
- React + TypeScript
- Vite (сборщик)

## Установка

### Вариант 1: Docker Compose (рекомендуется)

**⚠️ Важно:** Перед запуском убедитесь, что **Docker Desktop запущен**!

**Шаги запуска:**

1. **Запустите Docker Desktop** (если еще не запущен)
   - Откройте меню Пуск → найдите "Docker Desktop" → запустите
   - Дождитесь полной загрузки (1-2 минуты)
   - Проверьте: `docker ps` (должно работать без ошибок)

2. **Настройте переменные окружения:**
   ```powershell
   # Если файл .env еще не создан
   Copy-Item .env.example .env
   ```
   Откройте `.env` и измените секретные ключи (минимум 32 символа):
   - `JWT_SECRET`
   - `REFRESH_TOKEN_SECRET`

3. **Запустите все сервисы:**
   ```powershell
   docker-compose up -d
   ```

4. **Проверьте статус:**
   ```powershell
   docker-compose ps
   ```

5. **Откройте в браузере:**
   - Frontend: http://localhost:5173
   - Backend API: http://localhost:3000/health
   - MinIO Console: http://localhost:9001 (логин: minioadmin, пароль: minioadmin)

**Учетные данные по умолчанию:**
- Admin: `admin` / `admin123`
- Operator: `operator` / `operator123`
- Viewer: `viewer` / `viewer123`

**Проблемы с запуском?** Проверьте раздел ниже «Troubleshooting».

Подробнее о каждой службе и командах см. раздел «Docker Compose» в этом README.

### Вариант 2: Локальная установка

1. Установите зависимости для backend и frontend
2. Настройте переменные окружения
3. Запустите миграции базы данных
4. Запустите серверы

## Docker Compose

### Основные команды

```bash
# Запуск всех сервисов
docker-compose up -d

# Просмотр логов
docker-compose logs -f

# Перезапуск конкретного сервиса
docker-compose restart frontend

# Пересборка образов
docker-compose build frontend

# Остановка и очистка
docker-compose down           # только контейнеры
docker-compose down -v        # контейнеры + volumes
```

### Полезные контейнеры

- `backend` – Fastify API (`http://localhost:3000`)
- `frontend` – React UI (`http://localhost:5173`)
- `postgres` – база данных (порт `5432`)
- `redis` – очередь (порт `6379`)
- `minio` – объектное хранилище (`http://localhost:9001`)

## Troubleshooting

- **Docker не запускается** – убедитесь, что Docker Desktop запущен и `docker ps` работает без ошибок.
- **Нет `.env`** – скопируйте `.env.example` и задайте собственные секреты.
- **Порты заняты** – остановите конфликтующие приложения или измените порты в `docker-compose.yml`.
- **Backend не поднимается** – проверьте `docker-compose logs backend`, убедитесь, что PostgreSQL и Redis запущены.
- **PostgreSQL недоступен** – смотрите `docker-compose logs postgres`, при необходимости выполните `docker-compose restart postgres`.
- **MinIO не открывается** – убедитесь, что порты `9000/9001` свободны и контейнер `minio` в статусе `Up`.
