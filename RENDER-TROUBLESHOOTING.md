# Troubleshooting Render Deployment

## Проблема: 500 ошибки на всех API endpoints

### Симптомы:
```
GET https://android-automation-backend.onrender.com/api/devices 500 (Internal Server Error)
GET https://android-automation-backend.onrender.com/api/tasks 500 (Internal Server Error)
```

### Решение:

1. **Проверьте логи Backend на Render:**
   - Откройте Render Dashboard
   - Перейдите в Backend Service `android-automation-backend`
   - Откройте вкладку **Logs**
   - Найдите детали ошибки (теперь логируются с полным stack trace)

2. **Проверьте подключение к базе данных:**
   - Убедитесь, что PostgreSQL создан и работает
   - Проверьте, что `DATABASE_URL` правильно установлен в Environment Variables
   - Проверьте health check: `https://android-automation-backend.onrender.com/health`
     - Должен вернуть: `{"status":"ok","database":"connected",...}`
     - Если `"database":"disconnected"` - проблема с подключением к БД

3. **Проверьте миграции:**
   - Убедитесь, что миграции Prisma выполнены
   - В логах должно быть: `Prisma migrations applied successfully`

4. **Проверьте обязательные переменные окружения:**
   - `DATABASE_URL` - должен быть установлен автоматически из Blueprint
   - `JWT_SECRET` - должен быть сгенерирован автоматически
   - `REFRESH_TOKEN_SECRET` - должен быть сгенерирован автоматически

## Проблема: CORS ошибки

### Симптомы:
```
Access to XMLHttpRequest at 'https://android-automation-backend.onrender.com/api/devices' 
from origin 'https://android-automation-frontend.onrender.com' 
has been blocked by CORS policy
```

### Решение:

1. **Проверьте переменную CORS_ORIGIN:**
   - В Render Dashboard для Backend Service
   - Перейдите в **Environment**
   - Проверьте значение `CORS_ORIGIN`
   - Должно быть: `https://android-automation-frontend.onrender.com`

2. **Если переменная не установлена:**
   - Добавьте вручную:
     - **Key**: `CORS_ORIGIN`
     - **Value**: `https://android-automation-frontend.onrender.com`
   - Сохраните (Render автоматически перезапустит сервис)

3. **Проверьте логи Backend:**
   - После перезапуска в логах должно быть:
     ```
     CORS origins configured: ["https://android-automation-frontend.onrender.com"]
     ```

## Проблема: WebSocket не подключается

### Симптомы:
```
WebSocket connection failed
Endpoint: ws://localhost:3000/api/logs/stream
```

### Решение:

1. **Проверьте, что используется правильный WebSocket URL:**
   - В production должен быть: `wss://android-automation-backend.onrender.com/api/logs/stream`
   - Не `ws://localhost:3000`

2. **Проверьте переменную VITE_API_URL:**
   - В Render Dashboard для Static Site `android-automation-frontend`
   - Перейдите в **Environment**
   - Убедитесь, что установлено:
     - **Key**: `VITE_API_URL`
     - **Value**: `https://android-automation-backend.onrender.com`

3. **Проверьте CORS для WebSocket:**
   - WebSocket использует тот же CORS, что и HTTP
   - Убедитесь, что `CORS_ORIGIN` правильно настроен

## Проверка работоспособности

### 1. Health Check
```bash
curl https://android-automation-backend.onrender.com/health
```

Ожидаемый ответ:
```json
{
  "status": "ok",
  "timestamp": "2025-11-23T...",
  "database": "connected"
}
```

### 2. Проверка CORS
Откройте браузерную консоль на frontend и выполните:
```javascript
fetch('https://android-automation-backend.onrender.com/health')
  .then(r => r.json())
  .then(console.log)
```

Должно вернуть данные без CORS ошибок.

### 3. Проверка API
```bash
# Получите токен через /api/auth/login
curl -X POST https://android-automation-backend.onrender.com/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"your-password"}'

# Используйте токен для запроса
curl https://android-automation-backend.onrender.com/api/devices \
  -H "Authorization: Bearer YOUR_TOKEN"
```

## Частые ошибки

### "Missing required environment variable: DATABASE_URL"
- **Причина**: База данных не создана или не подключена
- **Решение**: Проверьте, что PostgreSQL создан в Blueprint и `DATABASE_URL` установлен

### "Prisma schema validation error"
- **Причина**: Проблема с `schema.prisma`
- **Решение**: Убедитесь, что файл закоммичен и не содержит синтаксических ошибок

### "Cannot find name 'process'"
- **Причина**: TypeScript не находит типы Node.js
- **Решение**: Убедитесь, что `@types/node` в `dependencies` (не `devDependencies`)

### "Request error" без деталей
- **Причина**: Старая версия error handler
- **Решение**: Обновите код и пересоберите (теперь логируются детали ошибок)

## Получение детальных логов

1. В Render Dashboard откройте Backend Service
2. Перейдите в **Logs**
3. Фильтруйте по:
   - `error` - для ошибок
   - `CORS` - для CORS проблем
   - `database` - для проблем с БД

## Перезапуск сервисов

Если изменения не применяются:

1. **Backend:**
   - Render Dashboard → Backend Service → **Manual Deploy** → **Deploy latest commit**

2. **Frontend:**
   - Render Dashboard → Static Site → **Manual Deploy** → **Deploy latest commit**

## Контакты для помощи

Если проблема не решена:
1. Проверьте логи Backend на Render
2. Проверьте логи Frontend (браузерная консоль)
3. Убедитесь, что все переменные окружения установлены
4. Проверьте, что миграции Prisma выполнены

