# Android Automation Agent

Android приложение для автоматизации браузеров и парсинга веб-сайтов.

## Требования

- Android 7.0+ (API 24+)
- Root доступ (для функций уникализации)
- Интернет соединение

## Структура проекта

```
android-agent/
├── app/
│   ├── src/main/
│   │   ├── java/com/automation/agent/
│   │   │   ├── MainActivity.kt
│   │   │   ├── services/
│   │   │   │   ├── ControllerService.kt      # Регистрация, heartbeat
│   │   │   │   ├── TaskExecutor.kt          # Выполнение задач
│   │   │   │   └── UniquenessService.kt     # Уникализация устройства
│   │   │   ├── browser/
│   │   │   │   ├── ChromeController.kt      # Управление Chrome
│   │   │   │   ├── WebViewController.kt     # Управление WebView
│   │   │   │   └── BrowserSelector.kt       # Выбор браузера
│   │   │   ├── automation/
│   │   │   │   ├── Navigator.kt             # Навигация
│   │   │   │   ├── Parser.kt                # Парсинг данных
│   │   │   │   └── Screenshot.kt           # Скриншоты
│   │   │   ├── network/
│   │   │   │   ├── ProxyManager.kt         # Управление прокси
│   │   │   │   └── ApiClient.kt            # API клиент
│   │   │   └── utils/
│   │   │       ├── RootUtils.kt            # Root операции
│   │   │       └── DeviceInfo.kt           # Информация об устройстве
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
└── README.md
```

## Основной функционал

### 1. Регистрация и Heartbeat
- Автоматическая регистрация устройства при первом запуске
- Периодическая отправка heartbeat каждые 30 секунд
- Автоматическое переподключение при потере связи

### 2. Выполнение задач
- Поддержка шагов: `navigate`, `wait`, `click`, `extract`, `screenshot`, `upload`
- Обработка ошибок с retry логикой
- Отправка результатов на backend

### 3. Браузер
- Поддержка Chrome (через Accessibility API + DevTools)
- Поддержка WebView (встроенный браузер)
- Автоматический выбор браузера с fallback

### 4. Парсинг
- Извлечение данных по CSS/XPath селекторам
- Парсинг adurl из рекламных ссылок (regex: `&adurl=([^&]+)`)
- Дедупликация доменов

### 5. Уникализация (требует root)
- Регенерация AAID/AndroidID
- Очистка Chrome/WebView data
- Изменение User-Agent
- Изменение timezone
- Изменение build.prop параметров
- Изменение GPS координат

## Настройка

### 1. Backend URL

Настройте URL backend сервера в `ApiClient`:

```kotlin
val apiClient = ApiClient("https://android-automation-backend.onrender.com")
```

### 2. Прокси

Добавьте прокси-серверы:

```kotlin
val proxyManager = ProxyManager()
val proxy = ProxyManager.parseSocks5("socks5://host:port:user:pass")
proxyManager.addProxy(proxy)
```

### 3. Root доступ

Приложение автоматически проверяет наличие root доступа для функций уникализации.

## Сборка

### Требования
- Android Studio Hedgehog | 2023.1.1 или новее
- JDK 17
- Android SDK 34

### Сборка APK

```bash
./gradlew assembleRelease
```

APK будет в `app/build/outputs/apk/release/app-release.apk`

### Подпись APK

Для установки на устройства требуется подписать APK:

1. Создайте keystore:
```bash
keytool -genkey -v -keystore android-agent.keystore -alias android-agent -keyalg RSA -keysize 2048 -validity 10000
```

2. Настройте `app/build.gradle.kts`:
```kotlin
android {
    signingConfigs {
        create("release") {
            storeFile = file("android-agent.keystore")
            storePassword = "your_password"
            keyAlias = "android-agent"
            keyPassword = "your_password"
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
        }
    }
}
```

## Установка

1. Включите "Установка из неизвестных источников" в настройках Android
2. Установите APK файл на устройство
3. Предоставьте необходимые разрешения (Интернет, Хранилище, Локация)
4. Если требуется root - предоставьте root доступ приложению

## Использование

1. Запустите приложение
2. Приложение автоматически зарегистрируется на backend
3. Backend будет отправлять задачи на выполнение
4. Результаты автоматически отправляются на backend

## API Endpoints

Приложение использует следующие endpoints backend:

- `POST /api/agent/register` - Регистрация устройства
- `POST /api/agent/heartbeat` - Отправка heartbeat
- `GET /api/agent/tasks` - Получение задач
- `POST /api/agent/tasks/:id/result` - Отправка результата
- `POST /api/agent/screenshot` - Загрузка скриншота

## Разработка

### Добавление нового функционала

1. Создайте класс в соответствующей директории
2. Добавьте зависимости в `build.gradle.kts` если нужно
3. Обновите `AndroidManifest.xml` если нужны новые разрешения

### Тестирование

```bash
./gradlew test
./gradlew connectedAndroidTest
```

## Лицензия

Proprietary - Все права защищены

## Контакты

Для вопросов и поддержки обращайтесь к разработчику.

