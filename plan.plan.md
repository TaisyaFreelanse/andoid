
 План разработки системы автоматизации Android браузеров

Этап 1: Проектирование (10 дней, $400)

1.1 Архитектура системы (PDF документ)

- **Компоненты:**
  - Backend Controller (Node.js/TypeScript + Fastify)
  - Android Agent (Kotlin APK)
  - Web UI (React/Vue минимальный)
  - PostgreSQL (основная БД)
  - Redis + Bull (очередь задач)
  - MinIO/S3 (хранение скриншотов)

- **Схема взаимодействия:**
  ```
  [Web UI] <-> [Backend API] <-> [PostgreSQL]
                              <-> [Redis/Bull Queue]
                              <-> [MinIO]
  
  [Android Agent] <-> [Backend API] (WebSocket/HTTP polling)
                   <-> [Proxy Server] (HTTP/HTTPS)
                   <-> [Semrush API] (через Backend)
  ```

- **Потоки данных:**
  - Регистрация устройства → Backend → PostgreSQL
  - Получение задачи → Agent → Выполнение → Результаты → Backend
  - Парсинг adurl → Agent → Backend → Semrush API → PostgreSQL
  - Скриншоты → Agent → Backend → MinIO → PostgreSQL (ссылка)

1.2 ERD (Entity Relationship Diagram)

**Основные сущности:**

- `devices` (id, name, android_id, aaid, ip_address, status, last_heartbeat, browser_type, created_at)
- `tasks` (id, name, type, config_json, status, device_id, created_at, started_at, completed_at)
- `task_steps` (id, task_id, step_type, step_config, order, status, result_json)
- `proxies` (id, host, port, username, password, type, status, country, timezone, last_used)
- `parsed_data` (id, task_id, url, ad_url, ad_domain, screenshot_path, parsed_at)
- `semrush_data` (id, domain, data_json, checked_at, expires_at)
- `device_fingerprints` (id, device_id, android_id, aaid, ua, timezone, latitude, longitude, build_prop_hash, created_at)
- `audit_logs` (id, user_id, action, resource_type, resource_id, details, timestamp)

 1.3 OpenAPI спецификация (черновик)

**Основные endpoints:**

- `POST /api/auth/login` - JWT аутентификация
- `GET /api/devices` - список устройств
- `POST /api/devices` - регистрация устройства
- `GET /api/devices/:id/status` - статус устройства
- `POST /api/tasks` - создание задачи
- `GET /api/tasks` - список задач
- `GET /api/tasks/:id/results` - результаты задачи
- `POST /api/proxies` - добавление прокси
- `GET /api/parsed-data` - экспорт данных (CSV/JSON)
- `GET /api/artifacts/:id` - получение скриншота
- `POST /api/devices/:id/stream/start` - запуск стриминга экрана
- `POST /api/devices/:id/stream/stop` - остановка стриминга экрана

**Agent endpoints:**

- `POST /api/agent/register` - регистрация агента
- `POST /api/agent/heartbeat` - heartbeat
- `GET /api/agent/tasks` - получение задач
- `POST /api/agent/tasks/:id/result` - отправка результата
- `POST /api/agent/screenshot` - загрузка скриншота
- `POST /api/agent/stream` - отправка скриншота для стриминга (WebSocket)

 1.4 План безопасности

- **Секреты:** 
  - Хранение в `.env` файлах (не коммитить)
  - Для продакшена: HashiCorp Vault или AWS Secrets Manager
  - Прокси credentials в зашифрованном виде в PostgreSQL

- **RBAC роли:**
  - `admin` - полный доступ
  - `operator` - создание задач, просмотр результатов
  - `viewer` - только просмотр

- **Audit log:**
  - Все действия пользователей (создание задач, изменение настроек)
  - Все действия агентов (регистрация, выполнение задач)
  - Логирование ошибок и подозрительной активности

**Результат этапа:** PDF с архитектурой, ERD диаграмма, OpenAPI spec (Swagger), документ по безопасности

---

 Этап 2: Backend + Очередь + Минимальный UI (20 дней, $1,700)

 2.1 Backend (Node.js/TypeScript + Fastify)

**Структура проекта:**


backend/
├── src/
│   ├── routes/
│   │   ├── auth.ts
│   │   ├── devices.ts
│   │   ├── tasks.ts
│   │   ├── proxies.ts
│   │   ├── parsed-data.ts
│   │   └── agent.ts
│   ├── services/
│   │   ├── device.service.ts
│   │   ├── task.service.ts
│   │   ├── proxy.service.ts
│   │   ├── semrush.service.ts
│   │   └── storage.service.ts
│   ├── models/
│   │   ├── device.model.ts
│   │   ├── task.model.ts
│   │   └── ...
│   ├── queue/
│   │   ├── task.processor.ts
│   │   └── bull.config.ts
│   ├── middleware/
│   │   ├── auth.middleware.ts
│   │   └── rbac.middleware.ts
│   └── utils/
│       ├── logger.ts
│       └── validator.ts
├── prisma/
│   └── schema.prisma
└── docker-compose.yml
```

**Основной функционал:**

- REST API с JWT аутентификацией
- RBAC middleware для проверки прав
- Интеграция с PostgreSQL через Prisma ORM
- Redis + Bull для очереди задач
- Интеграция с Semrush API (сервис для проверки доменов)
- Загрузка файлов в MinIO/S3
- WebSocket для real-time обновлений статуса устройств

**Файлы для реализации:**

- `src/routes/agent.ts` - endpoints для Android агента
- `src/services/semrush.service.ts` - интеграция с Semrush API
- `src/queue/task.processor.ts` - обработчик очереди задач
- `prisma/schema.prisma` - схема БД

 2.2 База данных (PostgreSQL)

**Миграции Prisma:**

- Создание всех таблиц согласно ERD
- Индексы для оптимизации запросов
- Foreign keys для связей

 2.3 Очередь задач (Redis + Bull)

**Типы задач:**

- `surfing` - серфинг по сайтам
- `parsing` - парсинг данных
- `uniqueness` - уникализация устройства
- `screenshot` - создание скриншота

**Очередь:**

- Приоритеты задач
- Retry механизм для failed задач
- Мониторинг очереди через Bull Board

 2.4 Минимальный Web UI

**Технологии:** React + TypeScript + Vite

**Страницы:**

- `/login` - авторизация
- `/devices` - список устройств (статус, последний heartbeat)
- `/tasks` - список задач (создание, просмотр статуса)
- `/tasks/:id` - детали задачи + результаты
- `/logs` - tail логов (WebSocket stream)
- `/artifacts` - просмотр скриншотов

**Компоненты:**

- DeviceList, TaskList, LogViewer, ScreenshotViewer
 2.5 Docker Compose

**Сервисы:**

- `backend` - Node.js приложение
- `postgres` - PostgreSQL
- `redis` - Redis
- `minio` - MinIO для хранения файлов
- `nginx` - reverse proxy (опционально)

**Файл:** `docker-compose.yml` с инструкциями запуска

**Результат этапа:** Рабочий backend, API, минимальный UI, Docker Compose, инструкции по запуску

---

## Этап 3: Android Agent (APK) (20 дней, $2,000)

 3.1 Структура проекта (Kotlin)


android-agent/
├── app/
│   ├── src/main/
│   │   ├── java/com/automation/agent/
│   │   │   ├── MainActivity.kt
│   │   │   ├── services/
│   │   │   │   ├── ControllerService.kt (регистрация, heartbeat)
│   │   │   │   ├── TaskExecutor.kt (выполнение задач)
│   │   │   │   └── UniquenessService.kt (уникализация)
│   │   │   ├── browser/
│   │   │   │   ├── ChromeController.kt
│   │   │   │   ├── WebViewController.kt
│   │   │   │   └── BrowserSelector.kt
│   │   │   ├── automation/
│   │   │   │   ├── Navigator.kt (navigate, wait, click)
│   │   │   │   ├── Parser.kt (CSS/XPath extract)
│   │   │   │   └── Screenshot.kt
│   │   │   ├── network/
│   │   │   │   ├── ProxyManager.kt
│   │   │   │   └── ApiClient.kt
│   │   │   └── utils/
│   │   │       ├── RootUtils.kt
│   │   │       └── DeviceInfo.kt
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
└── README.md
```

 3.2 Основной функционал агента

**Регистрация и heartbeat:**

- `ControllerService.kt` - фоновый сервис
- Регистрация при первом запуске (отправка device info)
- Heartbeat каждые 30 секунд
- Автоматическое переподключение при потере связи

**Выполнение задач:**

- `TaskExecutor.kt` - основной исполнитель
- Поддержка шагов: `navigate`, `wait`, `click`, `extract`, `screenshot`, `upload`
- Обработка ошибок и retry логика
- Отправка результатов на backend

**Браузер:**

- `BrowserSelector.kt` - выбор браузера (Chrome или WebView)
- `ChromeController.kt` - управление Chrome через Accessibility API + DevTools
- `WebViewController.kt` - встроенный WebView для парсинга
- Поддержка прокси для обоих вариантов

**Парсинг:**

- `Parser.kt` - извлечение данных по CSS/XPath селекторам
- Парсинг adurl из рекламных ссылок (regex: `&adurl=([^&]+)`)
- Дедупликация доменов (проверка перед сохранением)

**Уникализация (root):**

- `UniquenessService.kt` - сервис уникализации
- Регенерация AAID/AndroidID через `settings put secure android_id`
- Очистка Chrome/WebView data через `pm clear` и удаление папок
- Изменение User-Agent через build.prop или runtime
- Изменение timezone через `settings put global auto_time_zone`
- Автоматическая установка таймзоны в соответствии с геолокацией прокси-сервера (при назначении прокси определяется страна/регион и устанавливается соответствующая таймзона)
- Изменение build.prop параметров (model, manufacturer, etc.)
- Изменение геолокации (GPS координаты) через `settings put secure mock_location` и настройки Location Services
- Изменение IP-геолокации через прокси (уже реализовано в системе)
- **Важно:** Без изменения IMEI/serial/baseband (hardware level)

 3.3 Примеры сценариев (JSON/YAML)

**Сценарий 1: Парсинг рекламных баннеров на applicationsamplehub.com**

```json
{
  "name": "parse_ad_banners",
  "type": "parsing",
  "browser": "chrome",
  "proxy": "auto",
  "steps": [
    {"type": "navigate", "url": "https://applicationsamplehub.com/"},
    {"type": "wait", "duration": 3000},
    {"type": "extract", "selector": "a[href*='adurl']", "attribute": "href", "save_as": "ad_links"},
    {"type": "screenshot", "selector": ".ad-banner", "save_as": "banner_screenshot"},
    {"type": "navigate", "url": "https://applicationsamplehub.com/page2"},
    {"type": "wait", "duration": 3000},
    {"type": "extract", "selector": "a[href*='adurl']", "attribute": "href", "save_as": "ad_links"},
    {"type": "screenshot", "selector": ".ad-banner", "save_as": "banner_screenshot"}
  ],
  "post_process": {
    "extract_adurl": true,
    "deduplicate_domains": true,
    "check_semrush": true
  }
}
```

**Сценарий 2: Серфинг с прокруткой**

```json
{
  "name": "surf_and_scroll",
  "type": "surfing",
  "browser": "webview",
  "steps": [
    {"type": "navigate", "url": "https://thebirdidentifier.com/"},
    {"type": "wait", "duration": 2000},
    {"type": "scroll", "direction": "down", "pixels": 500},
    {"type": "wait", "duration": 2000},
    {"type": "screenshot", "save_as": "full_page"}
  ]
}
```

**Сценарий 3: Уникализация устройства**

```json
{
  "name": "uniqueness_device",
  "type": "uniqueness",
  "actions": [
    {"type": "regenerate_android_id"},
    {"type": "regenerate_aaid"},
    {"type": "clear_chrome_data"},
    {"type": "clear_webview_data"},
    {"type": "change_user_agent", "ua": "random"},
    {"type": "change_timezone", "timezone": "random"},
    {"type": "change_location", "latitude": "random", "longitude": "random"},
    {"type": "modify_build_prop", "params": {"model": "random", "manufacturer": "random"}}
  ]
}
```

 3.4 Сборка и установка

- APK с подписью для установки
- Исходный код Kotlin
- README с инструкциями по установке и настройке
- Требования: Android 7.0+, root права

**Результат этапа:** APK файл, исходники, примеры сценариев, документация

---

 Этап 4: Хранение и экспорт, артефакты (10 дней, $300)

4.1 Хранение скриншотов (MinIO/S3)

**Интеграция:**

- Backend сервис для загрузки файлов
- Agent отправляет скриншоты через multipart/form-data
- Сохранение в MinIO с организацией по папкам: `screenshots/{device_id}/{task_id}/{timestamp}.png`
- Генерация presigned URLs для доступа через UI

**Файл:** `src/services/storage.service.ts`

 4.2 Хранение результатов парсинга (PostgreSQL)

**Таблицы:**

- `parsed_data` - все спарсенные данные
- `semrush_data` - результаты проверки Semrush (с кешированием)

**Структура данных:**

```typescript
interface ParsedData {
  id: string;
  task_id: string;
  url: string;
  ad_url: string; 
  ad_domain: string; 
  screenshot_path: string; 
  parsed_at: Date;
}

interface SemrushData {
  id: string;
  domain: string;
  data_json: JSON; 
  checked_at: Date;
  expires_at: Date; 
}
```

 4.3 Экспорт данных

**API endpoints:**

- `GET /api/parsed-data/export?format=csv&date_from=...&date_to=...`
- `GET /api/parsed-data/export?format=json&date_from=...&date_to=...`

**Формат CSV:**

```csv
task_id,url,ad_url,ad_domain,screenshot_url,parsed_at,semrush_domain_rating,semrush_backlinks
```

**Формат JSON:**

- Массив объектов с полными данными включая вложенные объекты Semrush

**Файл:** `src/routes/parsed-data.ts` (export endpoints)

 4.4 API выгрузки

- RESTful API для получения данных программно
- Фильтрация по дате, устройству, задаче
- Пагинация для больших объемов данных

**Результат этапа:** MinIO интеграция, экспорт CSV/JSON, API выгрузки

---

 Этап 5: Интеграция, тестирование, передача (10 дней, $600)

5.1 Интеграционные тесты

**Тесты на 3-5 устройствах:**

- Регистрация устройств
- Создание и выполнение задач
- Парсинг рекламных баннеров
- Проверка Semrush API
- Уникализация устройств
- Загрузка скриншотов

**Smoke tests на 20 устройствах:**

- Одновременная регистрация всех устройств
- Параллельное выполнение задач
- Проверка стабильности heartbeat
- Нагрузочное тестирование backend

 5.2 Баг-фиксинг

- Исправление найденных багов
- Оптимизация производительности
- Улучшение обработки ошибок

 5.3 Документация

**Документы:**

- `DEPLOYMENT.md` - инструкция по развертыванию
- `API.md` - описание API endpoints
- `AGENT_SETUP.md` - установка и настройка агента
- `SCENARIOS.md` - примеры сценариев и их создание
- `TROUBLESHOOTING.md` - решение типичных проблем

 5.4 Checklist приёмки

- [ ] Все устройства регистрируются и отправляют heartbeat
- [ ] Задачи выполняются корректно на всех устройствах
- [ ] Парсинг adurl работает на всех тестовых сайтах
- [ ] Скриншоты сохраняются и доступны через UI
- [ ] Semrush API интегрирован и данные сохраняются
- [ ] Уникализация устройств работает (проверка через device info)
- [ ] Экспорт CSV/JSON работает
- [ ] UI отображает все данные корректно
- [ ] Система работает стабильно с 20 устройствами параллельно
- [ ] Удаленное управление через RDP работает

 5.5 Передача проекта

**Артефакты:**

- Исходный код backend (TypeScript)
- Исходный код Android agent (Kotlin)
- Docker Compose конфигурация
- OpenAPI спецификация (Swagger)
- ERD диаграмма
- Документация (все MD файлы)
- Примеры сценариев (JSON/YAML)
- Инструкции по развертыванию

**Результат этапа:** Протестированная система, документация, передача исходников

---

## Этап 6: Вывод экранов устройств в реальном времени (7–10 дней, $500)

**Функционал:**
- Стриминг экранов устройств в реальном времени (как в Taps Farm)
- Отображение сетки устройств в Web UI
- Обновление экранов каждые 1–2 секунды

**Android Agent:**
- `ScreenCaptureService.kt` — сервис захвата экрана
- Захват скриншотов через системные API (MediaProjection или root)
- Оптимизация размера изображений (сжатие JPEG, уменьшение разрешения)
- Отправка скриншотов через WebSocket на backend
- Настройка частоты обновления (1–2 секунды)

**Backend:**
- WebSocket сервер для стриминга скриншотов
- Обработка и ретрансляция скриншотов от агентов к UI
- Управление подключениями устройств
- Оптимизация трафика (сжатие, адаптивное качество)
- Endpoint для управления стримингом: `POST /api/devices/:id/stream/start`, `POST /api/devices/:id/stream/stop`

**Web UI:**
- Компонент `DeviceGrid` — сетка устройств (4x5 для 20 устройств)
- Компонент `DeviceScreen` — отображение экрана одного устройства
- Обновление экранов через WebSocket в реальном времени
- Настройки качества и частоты обновления
- Фильтрация и выбор устройств для отображения

**Технические детали:**
- Формат изображений: JPEG с качеством 70–80%
- Разрешение: адаптивное (максимум 720p для оптимизации)
- WebSocket протокол: бинарные сообщения для передачи изображений
- Обработка отключений: автоматическое переподключение

**Результат этапа:** Функционал стриминга экранов устройств, обновленный UI с сеткой устройств

---

 Технические детали реализации

 Парсинг adurl

- Regex паттерн: `&adurl=([^&]+)` или `adurl=([^&]+)`
- Извлечение из атрибута `href` ссылок
- Декодирование URL если необходимо
- Валидация домена перед отправкой в Semrush

 Интеграция с Semrush API

- Backend сервис `semrush.service.ts`
- Кеширование результатов на 24 часа
- Обработка rate limits
- Сохранение полного JSON ответа для анализа

 Уникализация устройств

- Выполнение через root команды (Runtime.exec)
- Проверка root прав перед выполнением
- Логирование всех изменений
- Откат изменений при ошибке (где возможно)
- Подмена геолокации: изменение GPS координат через mock location и настройки Location Services
- IP-геолокация меняется автоматически через прокси-серверы

 Поддержка выбора браузера

- Параметр `browser` в конфигурации задачи: `"chrome"` или `"webview"`
- Fallback на WebView если Chrome недоступен
- Единый интерфейс для обоих браузеров

 Прокси

- Ротация прокси между задачами
- Поддержка HTTP/HTTPS/SOCKS5
- Проверка работоспособности прокси перед использованием
- Автоматическая замена при ошибке подключения
- Автоматическая установка таймзоны устройства в соответствии с геолокацией прокси
  - При назначении прокси backend определяет страну/регион прокси (через GeoIP API или базу данных)
  - Backend отправляет команду агенту установить соответствующую таймзону
  - Агент устанавливает таймзону через root команды (`settings put global auto_time_zone`)
  - Это обеспечивает соответствие таймзоны устройства геолокации прокси для более реалистичного фингерпринта

---

## Общая стоимость проекта

**Этап 1:** Проектирование — 10 дней — $400  
**Этап 2:** Backend + Очередь + Минимальный UI — 20 дней — $1,700  
**Этап 3:** Android Agent (APK) — 20 дней — $2,000  
**Этап 4:** Хранение и экспорт, артефакты — 10 дней — $300  
**Этап 5:** Интеграция, тестирование, передача — 10 дней — $600  
**Этап 6:** Вывод экранов устройств в реальном времени — 7–10 дней — $500  

**Итого:** 77–80 дней работы, стоимость **$5,500**