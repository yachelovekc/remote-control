#!/usr/bin/env bash
cd /data/data/com.termux/files/home/remote-phone/controller-web
echo "Запуск веб-пульта RemotePhone..."
echo "Откройте в браузере: http://localhost:8088"
python3 -m http.server 8088
