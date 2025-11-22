 Миграции базы данных

Этот документ описывает процесс работы с миграциями Prisma для базы данных.

Первоначальная настройка

1. Убедитесь, что PostgreSQL запущен и доступен
2. Настройте `DATABASE_URL` в файле `.env`:
     DATABASE_URL=postgresql://user:password@localhost:5432/android_automation?schema=public
   

3. Создайте начальную миграцию:
   bash
   npm run prisma:migrate
   
   Это создаст все таблицы согласно схеме в `schema.prisma`

4. Заполните базу тестовыми данными (опционально):
   bash
   npm run prisma:seed
   

Полная настройка с нуля

Для полной настройки базы данных с нуля используйте:
bash
npm run db:setup


Эта команда:
1. Генерирует Prisma Client
2. Создает миграции
3. Заполняет базу тестовыми данными

 Работа с миграциями

 Создание новой миграции

После изменения `schema.prisma`:
bash
npm run prisma:migrate


Prisma предложит имя для миграции. Используйте описательные имена, например:
- `add_user_email_index`
- `add_proxy_timezone_field`
- `update_task_status_enum`

 Применение миграций в production

В production используйте:
bash
npm run prisma:migrate:deploy


Эта команда применяет все непримененные миграции без интерактивного режима.

 Сброс базы данных (только для разработки!)

**ВНИМАНИЕ:** Эта команда удалит все данные!

bash
npm run prisma:migrate:reset


Просмотр базы данных

Используйте Prisma Studio для визуального просмотра данных:
bash
npm run prisma:studio


Откроется веб-интерфейс на `http://localhost:5555`

Структура миграций

Миграции хранятся в папке `prisma/migrations/`. Каждая миграция содержит:
- `migration.sql` - SQL скрипт для применения изменений
- `migration_lock.toml` - информация о провайдере БД

 Индексы

Все индексы определены в `schema.prisma` с помощью директивы `@@index`. 

Основные индексы:
- `users.role` - для фильтрации по ролям
- `devices.status`, `devices.last_heartbeat` - для мониторинга устройств
- `tasks.user_id`, `tasks.device_id`, `tasks.status` - для фильтрации задач
- `parsed_data.task_id`, `parsed_data.ad_domain` - для поиска данных
- `audit_logs.user_id`, `audit_logs.timestamp` - для аудита

Foreign Keys

Все связи между таблицами настроены через Prisma relations:
- `User → Task` (onDelete: SetNull)
- `Device → Task` (onDelete: SetNull)
- `Task → TaskStep` (onDelete: Cascade)
- `Task → ParsedData` (onDelete: Cascade)
- `Proxy → Task` (onDelete: SetNull)
- `Device → DeviceFingerprint` (onDelete: Cascade)
- `User → AuditLog` (onDelete: SetNull)

Проверка схемы

Перед применением миграций проверьте схему:
```bash
npx prisma validate
```

Откат миграций

Prisma не поддерживает автоматический откат миграций. Для отката:
1. Создайте новую миграцию с обратными изменениями
2. Или используйте `prisma migrate reset` (удалит все данные!)

 Рекомендации

1. **Всегда делайте бэкап перед миграциями в production**
2. **Тестируйте миграции на тестовой БД**
3. **Используйте транзакции для критичных изменений**
4. **Документируйте изменения в комментариях к миграциям**

