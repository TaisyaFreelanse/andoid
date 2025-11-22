 Android Browser Automation - Web UI

Минимальный веб-интерфейс для системы автоматизации браузеров на Android устройствах.

 Технологии

- **React 18** - UI библиотека
- **TypeScript** - типизация
- **Vite** - сборщик и dev сервер
- **React Router** - маршрутизация
- **Axios** - HTTP клиент
- **Zustand** - управление состоянием

 Установка

1. Установите зависимости:
```bash
npm install
```

2. Настройте переменные окружения:
```bash

VITE_API_URL=http://localhost:3000/api
```

3. Запустите dev сервер:
```bash
npm run dev
```

Приложение будет доступно по адресу `http://localhost:5173`

 Сборка для production

```bash
npm run build
```

Собранные файлы будут в папке `dist/`

 Структура проекта

```
frontend/
├── src/
│   ├── api/          # API клиенты
│   ├── components/   # React компоненты
│   ├── pages/        # Страницы приложения
│   ├── stores/       # Zustand stores
│   ├── App.tsx       # Главный компонент
│   └── main.tsx      # Точка входа
├── public/           # Статические файлы
└── package.json
```

 Страницы

- `/login` - Авторизация
- `/devices` - Список устройств
- `/tasks` - Список задач
- `/tasks/:id` - Детали задачи
- `/logs` - Просмотр логов (WebSocket)
- `/artifacts` - Просмотр скриншотов

Компоненты

- `DeviceList` - Список устройств
- `DeviceCard` - Карточка устройства
- `TaskList` - Список задач
- `TaskCard` - Карточка задачи
- `TaskForm` - Форма создания задачи
- `LogViewer` - Просмотр логов
- `ScreenshotViewer` - Просмотр скриншотов
- `Layout` - Основной layout с навигацией
- `AuthGuard` - Защита маршрутов

 API

Все API запросы идут через `apiClient` из `src/api/client.ts`, который автоматически:
- Добавляет JWT токен в заголовки
- Обрабатывает 401 ошибки и обновляет токен
- Перенаправляет на `/login` при необходимости

Аутентификация

Используется JWT токены с refresh механизмом. Токены хранятся в localStorage через Zustand persist middleware.

