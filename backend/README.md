Android Browser Automation - Backend

Backend сервер для системы автоматизации браузеров на Android устройствах.

Технологии

- **Fastify** - веб-фреймворк
- **TypeScript** - типизация
- **Prisma** - ORM для работы с PostgreSQL
- **Bull** - очередь задач на Redis
- **MinIO** - хранилище файлов (S3-совместимое)
- **JWT** - аутентификация
- **Zod** - валидация данных

Требования

- Node.js 18+
- PostgreSQL 14+
- Redis 6+
- MinIO (опционально, для хранения файлов)

Установка

1. Установите зависимости:
```bash
npm install
```

2. Настройте переменные окружения:
```bash
cp .env.example .env

```

3. Настройте базу данных:
```bash

npm run db:setup


npm run prisma:generate


npm run prisma:migrate


npm run prisma:seed
```

**Примечание:** Для Windows используйте `.\scripts\init-db.ps1`, для Linux/Mac - `./scripts/init-db.sh`

4. Запустите сервер:
```bash

npm run dev


npm run build
npm start
```

 Структура проекта

```
backend/
├── src/
│   ├── routes/          # API маршруты
│   ├── services/        # Бизнес-логика
│   ├── middleware/     # Middleware (auth, rbac)
│   ├── utils/          # Утилиты (logger, validator)
│   ├── queue/          # Очередь задач (Bull)
│   ├── config.ts       # Конфигурация
│   └── server.ts       # Точка входа
├── prisma/
│   └── schema.prisma   # Схема базы данных
└── package.json
```

 API Endpoints

 Аутентификация
- `POST /api/auth/login` - Вход в систему
- `POST /api/auth/refresh` - Обновление токена

 Устройства
- `GET /api/devices` - Список устройств
- `GET /api/devices/:id` - Получить устройство
- `POST /api/devices` - Создать устройство
- `PATCH /api/devices/:id` - Обновить устройство
- `DELETE /api/devices/:id` - Удалить устройство

 Задачи
- `GET /api/tasks` - Список задач
- `GET /api/tasks/:id` - Получить задачу
- `POST /api/tasks` - Создать задачу
- `PATCH /api/tasks/:id` - Обновить задачу
- `DELETE /api/tasks/:id` - Удалить задачу

Прокси
- `GET /api/proxies` - Список прокси
- `GET /api/proxies/:id` - Получить прокси
- `POST /api/proxies` - Создать прокси
- `PATCH /api/proxies/:id` - Обновить прокси
- `DELETE /api/proxies/:id` - Удалить прокси

 Парсинг данных
- `GET /api/parsed-data` - Список спарсенных данных
- `GET /api/parsed-data/:id` - Получить данные
- `GET /api/parsed-data/export/csv` - Экспорт в CSV
- `GET /api/parsed-data/export/json` - Экспорт в JSON

 Агент (для Android устройств)
- `POST /api/agent/register` - Регистрация устройства
- `POST /api/agent/heartbeat` - Heartbeat от устройства
- `POST /api/agent/task/result` - Отправка результата задачи

 Переменные окружения

См. `.env.example` для полного списка переменных окружения.

Основные:
- `DATABASE_URL` - URL подключения к PostgreSQL
- `REDIS_HOST`, `REDIS_PORT` - Настройки Redis
- `JWT_SECRET` - Секретный ключ для JWT
- `MINIO_*` - Настройки MinIO
- `SEMRUSH_API_KEY` - API ключ Semrush
 Разработка

 Запуск в режиме разработки
```bash
npm run dev
```

 Генерация Prisma Client
```bash
npm run prisma:generate
```

 Миграции базы данных
```bash
npm run prisma:migrate
```

 Prisma Studio (GUI для БД)
```bash
npm run prisma:studio
```

 Тестирование

```bash
npm test
```

 Лицензия

ISC

