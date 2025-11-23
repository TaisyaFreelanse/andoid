# Настройка Frontend на Render

## Проблема: 404 ошибки при запросах к API

Если вы видите ошибки типа:
```
GET https://android-automation-frontend.onrender.com/api/devices 404 (Not Found)
```

Это означает, что frontend пытается делать запросы к своему собственному URL вместо backend URL.

## Решение

### 1. Установите переменную окружения VITE_API_URL

В Render Dashboard для Static Site `android-automation-frontend`:

1. Перейдите в **Environment** секцию
2. Добавьте переменную:
   - **Key**: `VITE_API_URL`
   - **Value**: `https://android-automation-backend.onrender.com`
3. Сохраните изменения
4. Пересоберите Static Site (Render сделает это автоматически)

### 2. Проверьте, что переменная установлена

После пересборки, откройте консоль браузера на frontend сайте и проверьте:
```javascript
console.log(import.meta.env.VITE_API_URL)
```

Должно вывести: `https://android-automation-backend.onrender.com`

### 3. Fallback конфигурация

Если `VITE_API_URL` не установлен, приложение автоматически использует:
- **Production**: `https://android-automation-backend.onrender.com`
- **Development (localhost)**: `http://localhost:3000`

### 4. SPA Routing

Файл `frontend/public/_redirects` настроен для правильной работы React Router на Render Static Site. Все маршруты будут перенаправляться на `index.html`.

## Проверка работы

После настройки:
1. Откройте https://android-automation-frontend.onrender.com
2. Откройте DevTools (F12) → Network
3. Попробуйте залогиниться или открыть страницу Devices
4. Проверьте, что запросы идут на `android-automation-backend.onrender.com`, а не на frontend URL

## Troubleshooting

### Запросы все еще идут на frontend URL

1. Убедитесь, что переменная `VITE_API_URL` установлена в Render Dashboard
2. Проверьте, что Static Site был пересобран после установки переменной
3. Очистите кеш браузера (Ctrl+Shift+R)

### CORS ошибки

Если видите CORS ошибки, проверьте:
1. В Render Dashboard для Backend сервиса установлена переменная:
   - `CORS_ORIGIN=https://android-automation-frontend.onrender.com`
2. Backend сервис перезапущен после установки переменной

