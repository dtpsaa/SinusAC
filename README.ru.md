# SinusAC

Античит на базе искусственного интеллекта для Minecraft Java и Bedrock. Телеметрия Combat и Fly анализируется закрытым ML-сервером SinusAI, а владелец сервера может следить за результатами через live-панель.

[English](README.md) · [Русский](README.ru.md)

[![Release](https://img.shields.io/github/v/release/dtpsaa/SinusAC?label=release)](https://github.com/dtpsaa/SinusAC/releases/latest)
[![Java](https://img.shields.io/badge/Java-21%2B-orange)](https://adoptium.net/)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21%2B-62b47a)](https://papermc.io/)
[![License](https://img.shields.io/badge/license-source--available-22d3c5)](LICENSE)

## Что такое SinusAC

SinusAC — серверный плагин античит-платформы SinusAI. Он собирает компактные параметры боя и движения и отправляет их в API SinusAI для анализа. Логика детекта и ML-модели остаются на закрытом сервере и не входят в этот репозиторий.

Основные возможности:

- ML-анализ Combat
- Настраиваемая Fly-проверка с отдельным режимом только для Bedrock
- Асинхронные HTTP-запросы без блокировки тиков Minecraft
- Пакетная отправка Fly-данных для снижения нагрузки
- Определение Java и Bedrock-игроков через Floodgate
- Русская и английская локализации
- Алерты, уровни нарушений, откаты и настраиваемые команды наказаний
- Live-панель с онлайном серверов, проверками, флагами и шансом читерства

## Важно перед установкой

Для работы SinusAC нужны активная подписка и ключ активации. Приобрести подписку и получить ключ можно через [Telegram-канал SinusAI](https://t.me/sinusai).

Плагин подключается к официальному API SinusAI. Срок действия и количество серверов определяются тарифом. Идентификатор сервера формируется автоматически из публичного IP и порта Minecraft — параметр `server.server-id` не требуется.

Панель пользователя: [panel.sinusai.tech](https://panel.sinusai.tech/)

## Требования

- Java 21 или новее
- Paper либо совместимое ядро на базе Paper для Minecraft 1.21+
- Доступ к интернету и API SinusAI
- Активный ключ SinusAI
- Floodgate, если требуется определение Bedrock-игроков

## Установка

1. Скачайте `SinusAC-<version>.jar` из раздела [GitHub Releases](https://github.com/dtpsaa/SinusAC/releases/latest).
2. Поместите JAR в папку `plugins/` сервера.
3. Один раз запустите сервер для создания конфига и локализаций.
4. Откройте `plugins/SinusAC/config.yml` и укажите `license-key`.
5. Проверьте настройки Combat, Fly и команд наказаний до включения автоматических блокировок.
6. Выполните обычный перезапуск сервера.

Не используйте PlugMan и похожие инструменты для замены плагина на работающем сервере. Для изменения конфигурации используйте `/sinusac reload`, а для обновления JAR — обычный перезапуск сервера.

## Файлы конфигурации

- `config.yml` — API, лицензия, сбор данных, настройки Combat и Fly
- `locale/en.yml` — английские сообщения
- `locale/ru.yml` — русские сообщения

По умолчанию используется английский язык. Для русского укажите `locale: "ru"` в `config.yml`.

Fly-проверка включается через `checks.fly.enabled`. Оставьте `checks.fly.bedrock-only: true`, чтобы проверять только Bedrock-игроков, либо установите `false`, чтобы включить Java-игроков.

## Основные команды

| Команда | Назначение |
| --- | --- |
| `/sinusac status` | Состояние API, лицензии, языка и проверок |
| `/sinusac alerts` | Включение и отключение алертов |
| `/sinusac holo` | Персональные голограммы анализа игроков |
| `/sinusac check <игрок>` | Ручной анализ собранной сессии |
| `/sinusac sessions` | Список активных сессий анализа |
| `/sinusac reload` | Перезагрузка конфига и локализации |

Также доступен алиас `/sac`.

## Права

| Право | Назначение | По умолчанию |
| --- | --- | --- |
| `sinusac.admin` | Полный доступ к командам | OP |
| `sinusac.alerts` | Получение алертов | OP |
| `sinusac.holo` | Использование персональных голограмм | OP |
| `anticheat.bypass` | Полный обход проверок | Никому |

## Сборка из исходников

```bash
git clone https://github.com/dtpsaa/SinusAC.git
cd SinusAC
mvn clean package
```

Готовый плагин будет создан здесь:

```text
target/SinusAC-<version>.jar
```

Самостоятельная сборка не предоставляет доступ к API. Для работы плагина всё равно необходим действующий ключ активации.

## Поддержка и сообщения об ошибках

- Покупка, ключи и поддержка: [t.me/sinusai](https://t.me/sinusai)
- Панель: [panel.sinusai.tech](https://panel.sinusai.tech/)
- Ошибки: [GitHub Issues](https://github.com/dtpsaa/SinusAC/issues)

При создании Issue укажите версию Minecraft, ядро сервера, версию Java и SinusAC, относящиеся к проблеме настройки и полный stack trace. Никогда не публикуйте свой ключ активации.

## Лицензия

SinusAC распространяется по модели source-available и не является программным обеспечением с открытым исходным кодом. Использование требует действующей подписки SinusAI и регулируется [SinusAI Source-Available License](LICENSE). Запрещены распространение, перепродажа, сублицензирование и обход активации или ограничений тарифа.

Copyright © 2026 ИП Царёв Александр Игоревич. SinusAI и SinusAC принадлежат правообладателю.
