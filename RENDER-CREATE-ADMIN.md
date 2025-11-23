# Создание администратора на Render

## Быстрый способ

### Вариант 1: Через Render Shell (рекомендуется)

1. В Render Dashboard откройте Backend Service `android-automation-backend`
2. Перейдите в **Shell** (или используйте SSH)
3. Выполните:
   ```bash
   cd backend
   npm run create:admin
   ```
4. Должно появиться:
   ```
   ✅ Admin user created/updated successfully!
   Username: admin
   Email: admin@example.com
   Password: admin123
   Role: admin
   ```

### Вариант 2: Через npm script

Если у вас есть доступ к серверу через SSH:
```bash
cd /opt/render/project/src/backend
npm run create:admin
```

### Вариант 3: Прямой вызов скрипта

```bash
cd backend
node scripts/create-admin.js
```

## Данные для входа

После выполнения скрипта используйте:

- **Username**: `admin`
- **Password**: `admin123`
- **Email**: `admin@example.com`

## Проверка

После создания администратора:

1. Откройте frontend: https://android-automation-frontend.onrender.com
2. Войдите с данными:
   - Username: `admin`
   - Password: `admin123`
3. Должны успешно войти в систему

## Обновление пароля

Если нужно изменить пароль, отредактируйте `backend/scripts/create-admin.js` и измените:
```javascript
const password = 'admin123'; // Ваш новый пароль
```

Затем запустите скрипт снова - он обновит существующего пользователя.

## Troubleshooting

### "Table users does not exist"
- Убедитесь, что миграции Prisma выполнены
- Выполните: `npx prisma migrate deploy`

### "Cannot find module 'bcrypt'"
- Убедитесь, что зависимости установлены: `npm ci`

### "Database connection error"
- Проверьте, что `DATABASE_URL` установлен в Environment Variables
- Проверьте подключение к базе данных

