 План безопасности системы автоматизации Android браузеров

 Обзор

Данный документ описывает меры безопасности, применяемые в системе автоматизации браузеров на Android устройствах. План охватывает аутентификацию, авторизацию, защиту данных, аудит и другие аспекты безопасности.

 1. Управление секретами

 1.1 Хранение секретов в разработке

**Метод:** Переменные окружения (`.env` файлы)

**Секреты, которые должны храниться в `.env`:**
- `JWT_SECRET` - секретный ключ для подписи JWT токенов
- `JWT_REFRESH_SECRET` - секретный ключ для refresh токенов
- `DATABASE_URL` - строка подключения к PostgreSQL (с паролем)
- `REDIS_URL` - строка подключения к Redis (если требуется пароль)
- `MINIO_ACCESS_KEY` - ключ доступа к MinIO
- `MINIO_SECRET_KEY` - секретный ключ MinIO
- `AHREFS_API_KEY` - API ключ для Ahrefs
- `AHREFS_SECRET_KEY` - секретный ключ Ahrefs

**Правила:**
- `.env` файлы НЕ должны коммититься в Git
- Добавить `.env` в `.gitignore`
- Создать `.env.example` с примерами (без реальных значений)
- Использовать библиотеку `dotenv` для загрузки переменных

**Пример `.env.example`:**
```env
JWT_SECRET=your-secret-key-here
JWT_REFRESH_SECRET=your-refresh-secret-here
DATABASE_URL=postgresql://user:password@localhost:5432/automation_db
REDIS_URL=redis://localhost:6379
MINIO_ACCESS_KEY=minioadmin
MINIO_SECRET_KEY=minioadmin
AHREFS_API_KEY=your-ahrefs-api-key
AHREFS_SECRET_KEY=your-ahrefs-secret-key
```

 1.2 Хранение секретов в продакшене

**Рекомендуемые решения:**

1. **HashiCorp Vault** (предпочтительно)
   - Централизованное хранение секретов
   - Динамические секреты
   - Шифрование на лету
   - Интеграция через API

2. **AWS Secrets Manager** (для AWS инфраструктуры)
   - Управление секретами через AWS Console
   - Автоматическая ротация
   - Интеграция с IAM

3. **Azure Key Vault** (для Azure инфраструктуры)
   - Централизованное хранилище
   - Интеграция с Azure AD

**Реализация:**
- Backend должен получать секреты при старте
- Кеширование секретов в памяти (не логировать)
- Периодическое обновление секретов (для ротации)

 1.3 Шифрование прокси credentials в БД

**Проблема:** Пароли прокси-серверов хранятся в базе данных и должны быть защищены.

**Решение:** Шифрование с использованием AES-256-GCM

**Реализация:**
```typescript

import crypto from 'crypto';

const ENCRYPTION_KEY = process.env.ENCRYPTION_KEY; 
const ALGORITHM = 'aes-256-gcm';

function encrypt(text: string): string {
  const iv = crypto.randomBytes(16);
  const cipher = crypto.createCipheriv(ALGORITHM, ENCRYPTION_KEY, iv);
  
  let encrypted = cipher.update(text, 'utf8', 'hex');
  encrypted += cipher.final('hex');
  
  const authTag = cipher.getAuthTag();
  
  return `${iv.toString('hex')}:${authTag.toString('hex')}:${encrypted}`;
}

function decrypt(encryptedText: string): string {
  const [ivHex, authTagHex, encrypted] = encryptedText.split(':');
  const iv = Buffer.from(ivHex, 'hex');
  const authTag = Buffer.from(authTagHex, 'hex');
  
  const decipher = crypto.createDecipheriv(ALGORITHM, ENCRYPTION_KEY, iv);
  decipher.setAuthTag(authTag);
  
  let decrypted = decipher.update(encrypted, 'hex', 'utf8');
  decrypted += decipher.final('utf8');
  
  return decrypted;
}
```

**Хранение в БД:**
- Поле `proxies.password_encrypted` содержит зашифрованный пароль
- Расшифровка происходит только при использовании прокси
- Никогда не логировать расшифрованные пароли

 2. Аутентификация и авторизация

2.1 JWT токены

**Структура токена:**
```json
{
  "userId": "uuid",
  "username": "string",
  "role": "admin|operator|viewer",
  "iat": 1234567890,
  "exp": 1234654290
}
```

**Параметры:**
- **Access Token:** Время жизни 24 часа
- **Refresh Token:** Время жизни 7 дней
- **Алгоритм:** HS256
- **Секретный ключ:** Минимум 256 бит (32 байта)

**Безопасность:**
- Токены передаются только через HTTPS
- Хранение токенов в памяти (не в localStorage для продакшена)
- Проверка подписи при каждом запросе
- Отзыв токенов через blacklist в Redis

### 2.2 Токены агентов

**Отличия от пользовательских токенов:**
- Долгоживущие (30 дней или бессрочные)
- Отдельный секретный ключ (`AGENT_JWT_SECRET`)
- Ограниченный набор endpoints
- Привязка к `device_id`

**Структура:**
```json
{
  "deviceId": "uuid",
  "androidId": "string",
  "type": "agent",
  "iat": 1234567890,
  "exp": 1234654290
}
```

 2.3 RBAC (Role-Based Access Control)

**Роли и права доступа:**

 Admin (Администратор)
- Полный доступ ко всем ресурсам
- Управление пользователями
- Управление устройствами (удаление)
- Управление прокси
- Просмотр audit логов
- Изменение системных настроек

 Operator (Оператор)
- Создание и управление задачами
- Просмотр устройств и их статусов
- Просмотр результатов парсинга
- Экспорт данных
- Просмотр скриншотов
- **НЕ может:** удалять устройства, управлять пользователями, просматривать audit логи

 Viewer (Наблюдатель)
- Только просмотр данных
- Просмотр устройств (без управления)
- Просмотр задач (без создания)
- Просмотр результатов парсинга
- Просмотр скриншотов
- **НЕ может:** создавать задачи, управлять устройствами, экспортировать данные

**Реализация middleware:**
```typescript
function requireRole(...allowedRoles: string[]) {
  return async (request: FastifyRequest, reply: FastifyReply) => {
    const user = request.user; // из JWT токена
    
    if (!allowedRoles.includes(user.role)) {
      return reply.code(403).send({
        error: 'Forbidden',
        message: 'Insufficient permissions'
      });
    }
  };
}


app.post('/api/tasks', {
  preHandler: [authenticate, requireRole('admin', 'operator')]
}, createTask);
```

 3. Защита данных

 3.1 Шифрование соединений

**HTTPS:**
- Обязательное использование HTTPS в продакшене
- TLS 1.2 или выше
- Валидный SSL сертификат (Let's Encrypt или коммерческий)
- HSTS заголовки для принудительного HTTPS

**Внутренние соединения:**
- PostgreSQL: SSL соединение (если возможно)
- Redis: TLS соединение (если доступно)
- MinIO: HTTPS для доступа

 3.2 Валидация входных данных

**Правила:**
- Валидация всех входных данных на уровне API
- Использование библиотеки `zod` или `joi` для валидации
- Санитизация строковых данных (защита от XSS)
- Ограничение размера запросов (body-parser limits)

**Пример валидации:**
```typescript
import { z } from 'zod';

const TaskCreateSchema = z.object({
  name: z.string().min(1).max(255),
  type: z.enum(['surfing', 'parsing', 'uniqueness', 'screenshot']),
  device_id: z.string().uuid().optional(),
  config_json: z.object({}).passthrough()
});

app.post('/api/tasks', async (request, reply) => {
  try {
    const data = TaskCreateSchema.parse(request.body);
    
  } catch (error) {
    return reply.code(400).send({ error: 'Validation error', details: error });
  }
});
```

 3.3 Защита от SQL Injection

**Методы:**
- Использование ORM (Prisma) с параметризованными запросами
- Никогда не использовать строковую конкатенацию для SQL
- Валидация всех параметров перед использованием

 3.4 Защита от XSS

**Методы:**
- Экранирование HTML в выходных данных
- Использование Content Security Policy (CSP) заголовков
- Валидация и санитизация пользовательского ввода

 3.5 Rate Limiting

**Ограничения:**
- **API endpoints:** 100 запросов в минуту на IP
- **Login endpoint:** 5 попыток в минуту на IP
- **Agent endpoints:** 1000 запросов в минуту на device_id
- **File upload:** 10 загрузок в минуту на device_id

**Реализация:**
```typescript
import rateLimit from '@fastify/rate-limit';

app.register(rateLimit, {
  max: 100,
  timeWindow: '1 minute'
});


app.register(rateLimit, {
  max: 5,
  timeWindow: '1 minute'
}, { prefix: '/api/auth/login' });
```

 4. Audit логирование

 4.1 Что логируется

**Действия пользователей:**
- Вход в систему (успешный/неуспешный)
- Создание, изменение, удаление задач
- Управление устройствами
- Управление прокси
- Экспорт данных
- Изменение настроек системы

**Действия агентов:**
- Регистрация устройства
- Heartbeat (периодически)
- Получение задач
- Отправка результатов
- Загрузка скриншотов

**Системные события:**
- Ошибки аутентификации
- Попытки доступа к защищенным ресурсам
- Подозрительная активность (множественные неудачные попытки входа)
- Изменения в конфигурации системы

### 4.2 Структура audit лога

```typescript
interface AuditLog {
  id: string;
  user_id: string | null; 
  action: string; 
  resource_type: string; 
  resource_id: string | null;
  details: {
    ip_address?: string;
    user_agent?: string;
    changes?: object; 
    error?: string; 
  };
  timestamp: Date;
}
```

 4.3 Хранение логов

**База данных:**
- Таблица `audit_logs` в PostgreSQL
- Индексы на `user_id`, `resource_type`, `resource_id`, `timestamp`
- Ротация старых логов (архивация через 90 дней, удаление через 1 год)

**Файловые логи:**
- Структурированные JSON логи для анализа
- Ротация по дням
- Хранение последних 30 дней

 4.4 Мониторинг подозрительной активности

**Триггеры для алертов:**
- Более 5 неудачных попыток входа с одного IP за 10 минут
- Попытки доступа к несуществующим ресурсам (404) более 10 раз за минуту
- Необычные паттерны запросов от агентов
- Изменения в критичных настройках системы

**Реализация:**
```typescript
async function logSuspiciousActivity(
  action: string,
  details: object,
  request: FastifyRequest
) {
  await auditLogService.create({
    user_id: null,
    action: 'suspicious_activity',
    resource_type: 'system',
    resource_id: null,
    details: {
      detected_action: action,
      ip_address: request.ip,
      user_agent: request.headers['user-agent'],
      ...details
    }
  });
  
  
  await alertService.sendAlert({
    level: 'warning',
    message: `Suspicious activity detected: ${action}`,
    details
  });
}
```

 5. Безопасность Android агента

 5.1 Защита токена агента

**Хранение:**
- Токен хранится в зашифрованном виде в SharedPreferences
- Использование Android Keystore для шифрования
- Токен не должен логироваться

**Передача:**
- Токен передается только через HTTPS
- В заголовке `Authorization: Bearer <token>`
- Проверка сертификата сервера (certificate pinning опционально)

 5.2 Защита root команд

**Проверки:**
- Валидация всех root команд перед выполнением
- Whitelist разрешенных команд
- Логирование всех root операций
- Откат изменений при ошибке (где возможно)

**Whitelist команд:**
```kotlin
val ALLOWED_ROOT_COMMANDS = setOf(
    "settings put secure android_id",
    "settings put global auto_time_zone",
    "pm clear com.android.chrome",
    "pm clear com.google.android.webview"
    // и т.д.
)

fun executeRootCommand(command: String): Boolean {
    if (!ALLOWED_ROOT_COMMANDS.any { command.startsWith(it) }) {
        logError("Blocked unauthorized root command: $command")
        return false
    }
    // выполнение команды
}
```

 6. Безопасность файлов

6.1 Загрузка файлов

**Ограничения:**
- Максимальный размер файла: 10 MB
- Разрешенные типы: PNG, JPEG (для скриншотов)
- Валидация MIME типа
- Сканирование на вирусы (опционально, через ClamAV)

**Хранение:**
- Файлы хранятся в MinIO/S3 с уникальными именами
- Presigned URLs с ограниченным временем жизни (1 час)
- Нет прямого доступа к файлам без авторизации

 6.2 Доступ к артефактам

**Правила:**
- Доступ только для авторизованных пользователей
- Проверка прав доступа (пользователь должен иметь доступ к задаче)
- Presigned URLs с истечением
- Логирование всех запросов к файлам

 7. Резервное копирование

 7.1 База данных

**Политика:**
- Ежедневные автоматические бэкапы
- Хранение последних 7 дней
- Еженедельные бэкапы (хранение 4 недели)
- Ежемесячные бэкапы (хранение 12 месяцев)
- Шифрование бэкапов
- Тестирование восстановления раз в месяц

 7.2 Файлы

**Политика:**
- Репликация MinIO/S3
- Версионирование файлов
- Географическая репликация (для продакшена)

 8. Мониторинг и инциденты

8.1 Мониторинг безопасности

**Метрики:**
- Количество неудачных попыток входа
- Количество подозрительных действий
- Количество ошибок аутентификации
- Время отклика API
- Доступность сервисов

**Инструменты:**
- Prometheus + Grafana для метрик
- ELK Stack для логов
- Sentry для отслеживания ошибок

8.2 План реагирования на инциденты

**Шаги:**
1. Обнаружение инцидента (мониторинг, алерты)
2. Изоляция затронутых систем (если необходимо)
3. Анализ инцидента
4. Устранение уязвимости
5. Восстановление данных (если необходимо)
6. Документирование инцидента
7. Обновление политик безопасности

9. Соответствие требованиям

 9.1 GDPR (если применимо)

- Право на удаление данных
- Право на экспорт данных
- Уведомление о утечках данных
- Минимизация собираемых данных

 9.2 Логирование и прозрачность

- Все действия пользователей логируются
- Пользователи могут просматривать свои audit логи
- Администраторы имеют доступ ко всем логам

 10. Чеклист безопасности

Перед развертыванием в продакшен:

- [ ] Все секреты хранятся в безопасном хранилище (Vault/Secrets Manager)
- [ ] HTTPS настроен и работает
- [ ] SSL сертификат валиден
- [ ] Rate limiting настроен
- [ ] Валидация входных данных реализована
- [ ] SQL injection защита (ORM)
- [ ] XSS защита реализована
- [ ] Audit логирование работает
- [ ] Мониторинг безопасности настроен
- [ ] Бэкапы настроены и протестированы
- [ ] План реагирования на инциденты готов
- [ ] Документация по безопасности обновлена
- [ ] Пентест проведен (опционально, но рекомендуется)

 Заключение



