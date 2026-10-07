<p align="center"><img src="docs/icon.png" width="128" alt="Zapret Hub"></p>

# Zapret Hub

Локальный менеджер для [zapret-discord-youtube](https://github.com/Flowseal/zapret-discord-youtube) и [tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy). Один файл `ZapretHub.exe` (~1,4 МБ), без серверов: всё скачивается напрямую с GitHub.

**Стек:** C# / .NET Framework 4.8 (встроен в Windows 10/11) + WebView2 (интерфейс на HTML/CSS). Библиотеки WebView2 вшиты в exe.

## Возможности
- **zapret:** запуск стратегии вручную или установка службой Windows; Game Filter, IPSet (loaded/none/any), замена активных фейков, редактор списков доменов и IP, обновление ipset-списка и hosts.
- **Поиск ALT:** порт `utils/test zapret.ps1` — стандартный тест (HTTP/TLS1.2/TLS1.3 + ping) и тест DPI 16–20 КБ, рейтинг стратегий, запуск лучшей в один клик. Свежие стратегии из ветки `main` можно загрузить ещё до релиза.
- **TG WS Proxy:** установка, запуск и остановка, редактор конфига, ссылка `tg://proxy` и кнопка «Подключить Telegram», проверка DC, журнал.
- **Диагностика:** все проверки из `service.bat` (BFE, прокси, TCP timestamps, Adguard, Killer, Intel, Check Point, SmartByte, VPN, DoH, hosts, WinDivert, конфликтующие обходы) с кнопками исправления; очистка кэша Discord.
- **Обновления:** проверка и установка обеих утилит; пользовательские списки и настройки сохраняются. Можно включить автоустановку.
- Трей, автозапуск через Планировщик задач (без запроса UAC), уведомления.

## Сборка
```
dotnet build -c Release
```
Результат: `bin/Release/net48/ZapretHub.exe`. Для запуска нужны права администратора (их требуют winws/WinDivert).

## Где лежат данные
- Утилиты по умолчанию: `C:\ZapretHub\zapret`, `C:\ZapretHub\tg-ws-proxy` (меняются в настройках). Если zapret уже установлен, приложение найдёт его по службе или по запущенному winws.exe.
- Настройки, журналы и профиль WebView2: `%LOCALAPPDATA%\ZapretHub`.
- TgWsProxy работает в портативном режиме (`TgWsProxy_data` рядом с exe). Если конфиг уже есть в `%APPDATA%\TgWsProxy`, он импортируется.

## Благодарности
Вся работа по обходу — заслуга [Flowseal](https://github.com/Flowseal) и [bol-van/zapret](https://github.com/bol-van/zapret). Zapret Hub — только удобная оболочка над их утилитами.

## Лицензия
[MIT](LICENSE) © 2026 prokrastinaciya
