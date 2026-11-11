#!/usr/bin/env bash
# ==============================================================================
# RemotePhone - Быстрая настройка второго телефона через ADB (1 раз)
# ==============================================================================

set -e

PKG="com.remotephone.agent"
SERVICE_A11Y="com.remotephone.agent/.service.RemoteAccessibilityService"

echo "=================================================="
echo " Настройка второго телефона (RemotePhone Agent)"
echo "=================================================="

# Проверка наличия ADB
if ! command -v adb &> /dev/null; then
    echo "[-] Ошибка: adb не найден в системе. Установите adb (например: pkg install android-tools в Termux или на ПК)."
    exit 1
fi

# Проверка подключенных устройств
echo "[*] Поиск подключенных устройств..."
DEVICES=$(adb devices | grep -v "List" | grep "device$" | awk '{print $1}')

if [ -z "$DEVICES" ]; then
    echo "[-] Ошибка: Ни один телефон не обнаружен через ADB."
    echo "    1. Включите 'Отладка по USB' (USB Debugging) в параметрах разработчика на втором телефоне."
    echo "    2. Подключите его кабелем к ПК/основному телефону или подключитесь по Wi-Fi: adb connect <IP>:5555"
    exit 1
fi

echo "[+] Обнаружено устройство: $DEVICES"

# Если передан аргумент с APK, устанавливаем
APK_PATH="$1"
if [ -n "$APK_PATH" ] && [ -f "$APK_PATH" ]; then
    echo "[*] Установка APK: $APK_PATH ..."
    adb install -r -g "$APK_PATH"
else
    echo "[!] Путь к APK не указан или файл не найден. Пропускаем установку, настраиваем разрешения для уже установленного приложения..."
fi

echo "[*] Выдача разрешений через ADB (Автономная работа без всплывающих окон)..."

# 1. Захват экрана без подтверждения
echo " -> Разрешение на трансляцию экрана (PROJECT_MEDIA)..."
adb shell appops set "$PKG" PROJECT_MEDIA allow || true

# 2. Доступ ко всем файлам
echo " -> Разрешение на доступ к хранилищу (MANAGE_EXTERNAL_STORAGE)..."
adb shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow || true

# 3. Камера, микрофон, уведомления
echo " -> Разрешения на камеру, микрофон и уведомления..."
adb shell pm grant "$PKG" android.permission.CAMERA || true
adb shell pm grant "$PKG" android.permission.RECORD_AUDIO || true
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true

# 4. Включение Accessibility Service для кликов и жестов
echo " -> Автоматическое включение Accessibility Service..."
adb shell settings put secure enabled_accessibility_services "$SERVICE_A11Y"
adb shell settings put secure accessibility_enabled 1

# 5. Исключение из энергосбережения (Doze mode)
echo " -> Отключение оптимизации батареи..."
adb shell dumpsys deviceidle whitelist +"$PKG" || true
adb shell cmd appops set "$PKG" RUN_ANY_IN_BACKGROUND allow || true

echo ""
echo "=================================================="
echo " [OK] Все разрешения успешно выданы!"
echo " Второй телефон готов к полностью автономной работе."
echo "=================================================="
