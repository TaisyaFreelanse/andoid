@echo off
REM Простой скрипт для просмотра логов Android Agent
REM Использование: check-logs-simple.bat

echo === Android Agent Log Viewer ===
echo.

REM Проверка подключенных устройств
adb devices | findstr "device$" >nul
if errorlevel 1 (
    echo ОШИБКА: Устройство не подключено!
    echo.
    echo Инструкции:
    echo 1. Подключите устройство через USB
    echo 2. Включите 'Отладка по USB' в настройках разработчика
    echo 3. Разрешите отладку на устройстве
    echo 4. Запустите скрипт снова
    pause
    exit /b 1
)

echo Устройство подключено
echo.
echo Очистка старого буфера logcat...
adb logcat -c

echo.
echo Запуск logcat с фильтрацией...
echo Нажмите Ctrl+C для остановки
echo.
echo === ЛОГИ ===
echo.

REM Запуск logcat с фильтрацией через findstr
adb logcat | findstr /C:"ControllerService" /C:"UNIQUENESS" /C:"TASK EXECUTION" /C:"LogInterceptor" /C:"AutoLogcatSender" /C:"App" /C:"MainActivity"

