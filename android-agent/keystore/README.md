# Keystore для подписи APK

## Создание debug keystore

Debug keystore создается автоматически при первой сборке. Для ручного создания:

```bash
keytool -genkey -v -keystore debug.keystore -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
```

## Создание release keystore

Для production сборки создайте release keystore:

```bash
keytool -genkey -v -keystore release.keystore -alias automation-agent -keyalg RSA -keysize 2048 -validity 10000
```

При создании вам будет предложено ввести:
- Пароль хранилища (storePassword)
- Имя и фамилия (CN)
- Организационная единица (OU)
- Организация (O)
- Город (L)
- Область/штат (ST)
- Код страны (C)
- Пароль ключа (keyPassword)

## Настройка подписи в build.gradle.kts

### Вариант 1: Через environment variables (рекомендуется)

```kotlin
signingConfigs {
    create("release") {
        storeFile = file(System.getenv("KEYSTORE_FILE") ?: "keystore/release.keystore")
        storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
        keyAlias = System.getenv("KEY_ALIAS") ?: "automation-agent"
        keyPassword = System.getenv("KEY_PASSWORD") ?: ""
    }
}
```

Установите переменные окружения:
```bash
export KEYSTORE_FILE=/path/to/release.keystore
export KEYSTORE_PASSWORD=your_store_password
export KEY_ALIAS=automation-agent
export KEY_PASSWORD=your_key_password
```

### Вариант 2: Через local.properties

Добавьте в `local.properties`:
```properties
KEYSTORE_FILE=keystore/release.keystore
KEYSTORE_PASSWORD=your_store_password
KEY_ALIAS=automation-agent
KEY_PASSWORD=your_key_password
```

## Безопасность

⚠️ **ВАЖНО:**
- Никогда не коммитьте release keystore в Git
- Храните keystore и пароли в безопасном месте
- Используйте разные keystore для debug и release
- Для CI/CD используйте секреты (GitHub Secrets, GitLab CI Variables и т.д.)

## .gitignore

Убедитесь, что в `.gitignore` добавлено:
```
*.keystore
*.jks
local.properties
```

