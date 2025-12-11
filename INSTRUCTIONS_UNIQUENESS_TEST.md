# Инструкция по тестированию уникализации

## 📋 Что создано

1. **uniqueness_test_frontend.json** - JSON конфигурация для фронтенда
2. **uniqueness_test_config.json** - Полная конфигурация с деталями прокси
3. **android-agent/app/src/main/assets/scenarios/uniqueness_with_proxy_us.json** - Сценарий для Android агента

## 🚀 Шаги для тестирования

### 1. Сборка APK

**Вариант A: Через Android Studio (рекомендуется)**
1. Откройте Android Studio
2. Откройте проект: `C:\Users\GameOn-DP\StudioProjects\andoid\android-agent`
3. Build > Build Bundle(s) / APK(s) > Build APK(s)
4. Выберите вариант: `devDebug`
5. APK будет в: `app/build/outputs/apk/dev/debug/app-dev-debug.apk`

**Вариант B: Через командную строку**
```powershell
cd android-agent
.\gradlew.bat assembleDevDebug --no-configuration-cache
```

### 2. Копирование APK

После сборки скопируйте APK в целевую папку:
```powershell
$source = "android-agent\app\build\outputs\apk\dev\debug\app-dev-debug.apk"
$target = "C:\Users\GameOn-DP\StudioProjects\andoid\android-agent\app\build\outputs\apk\dev\debug\app-dev-debug.apk"

# Создать целевую папку если не существует
$targetDir = Split-Path -Path $target -Parent
if (-not (Test-Path $targetDir)) {
    New-Item -ItemType Directory -Path $targetDir -Force
}

# Копировать APK
Copy-Item -Path $source -Destination $target -Force
```

Или используйте скрипт:
```powershell
.\build-and-copy-apk.ps1
```

### 3. Создание задачи через фронтенд

1. Откройте фронтенд: https://android-automation-frontend.onrender.com/tasks
2. Нажмите "СОЗДАТЬ ЗАДАЧУ"
3. Заполните форму:
   - **НАЗВАНИЕ**: Комплексная уникализация с прокси (США)
   - **ТИП**: Уникализация
   - **УСТРОЙСТВО**: Выберите ваше устройство (должно быть online)
   - **КОНФИГУРАЦИЯ (JSON)**: Скопируйте содержимое файла `uniqueness_test_frontend.json`

### 4. JSON для фронтенда

Скопируйте содержимое файла `uniqueness_test_frontend.json` в поле "КОНФИГУРАЦИЯ (JSON)":

```json
{
  "id": "uniqueness_with_proxy_us",
  "name": "Комплексная уникализация с прокси (США)",
  "description": "Полная проверка уникализации с мобильными прокси США, авторотацией IP каждые 10 минут",
  "version": "1.0",
  "type": "uniqueness",
  "requires_root": true,
  "timeout": 180000,
  "config": {
    "backup_before": true,
    "reboot_after": false,
    "verify_changes": true,
    "log_detailed": true,
    "auto_detect_country": true,
    "use_proxy_geolocation": true,
    "proxy_rotation_interval": 600000,
    "proxy_rotation_enabled": true
  },
  "actions": [
    {
      "id": "action_1",
      "type": "detect_proxy_location",
      "save_as": "proxy_location",
      "description": "Определение геолокации прокси"
    },
    {
      "id": "action_2",
      "type": "regenerate_android_id",
      "description": "Регенерация AndroidID"
    },
    {
      "id": "action_3",
      "type": "regenerate_aaid",
      "description": "Регенерация AAID"
    },
    {
      "id": "action_4",
      "type": "clear_chrome_data",
      "description": "Очистка Chrome data"
    },
    {
      "id": "action_5",
      "type": "clear_webview_data",
      "description": "Очистка WebView data"
    },
    {
      "id": "action_6",
      "type": "change_user_agent",
      "ua": "random",
      "description": "Изменение User-Agent"
    },
    {
      "id": "action_7",
      "type": "change_timezone",
      "timezone": "auto",
      "country_code": "US",
      "use_proxy_timezone": true,
      "description": "Автоматическая установка timezone по прокси"
    },
    {
      "id": "action_8",
      "type": "change_location",
      "latitude": "auto",
      "longitude": "auto",
      "country_code": "US",
      "use_proxy_location": true,
      "description": "Автоматическая установка GPS по прокси"
    },
    {
      "id": "action_9",
      "type": "modify_build_prop",
      "params": {
        "ro.product.model": "random",
        "ro.product.manufacturer": "random",
        "ro.product.brand": "random"
      },
      "description": "Изменение build.prop"
    }
  ],
  "post_process": {
    "verify_android_id_changed": true,
    "verify_aaid_changed": true,
    "verify_user_agent_changed": true,
    "verify_timezone_matches_proxy": true,
    "verify_location_matches_proxy": true,
    "verify_build_prop_changed": true,
    "log_new_fingerprint": true,
    "send_to_backend": true
  }
}
```

## 📝 Прокси для добавления в Backend

Перед тестированием добавьте прокси в базу данных через API или админ-панель:

1. **socks5://x398.fxdx.in:18577:bmusproxy023054:n6zpj7v773wa** (NY, America/New_York)
2. **socks5://x356.fxdx.in:14517:bmusproxy294603:pkrygbfbby73** (CA, America/Los_Angeles)
3. **socks5://x468.fxdx.in:13873:bmusproxy054045:s35cbq2gnmvv** (TX, America/Chicago)
4. **socks5://x343.fxdx.in:14653:bmusproxy233723:dmsbx95cn5sr** (FL, America/New_York)
5. **socks5://x335.fxdx.in:14613:bmusproxy285133:Rc4uFGanCj0e5** (IL, America/Chicago)

Все прокси:
- Тип: SOCKS5
- Страна: US
- Мобильные: да
- Авторотация IP: каждые 10 минут

## ✅ Что будет протестировано

Согласно plan.plan.md (307-318):

1. ✅ Регенерация AAID/AndroidID через `settings put secure android_id`
2. ✅ Очистка Chrome/WebView data через `pm clear` и удаление папок
3. ✅ Изменение User-Agent через build.prop или runtime
4. ✅ Изменение timezone через `settings put global auto_time_zone`
5. ✅ Автоматическая установка таймзоны по геолокации прокси
6. ✅ Изменение build.prop параметров (model, manufacturer, etc.)
7. ✅ Изменение GPS координат через `settings put secure mock_location`
8. ✅ Автоматическая установка GPS по геолокации прокси

## ⚠️ Требования

- Устройство должно иметь root-доступ
- Устройство должно быть online в системе
- Прокси должны быть добавлены в базу данных

