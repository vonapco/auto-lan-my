# Auto-Lan
![Screenshot](docs/screen_en.png)
![Screenshot](docs/screen_cn.png)

## О проекте
Auto-Lan - это мод для Minecraft, который значительно расширяет возможности встроенного LAN-сервера, делая его более функциональным и удобным для совместной игры. Код мода вдохновлен проектом [Custom-LAN](https://github.com/DimiDimit/Custom-LAN), но со значительными дополнениями и улучшениями.

## Основные функции

### Настройка встроенного сервера
* Настройка различных параметров интегрированного сервера:
  * Установленное максимальное количество игроков - 5
  * Установка игрового режима по умолчанию
* Использование амперсандов (`&`) для [кодов форматирования](https://minecraft.wiki/w/Formatting_codes) вместо знаков параграфа (`§`)
* Поддержка переменных в MOTD (например, `${username}`, `${world}`) для динамического отображения информации

### Управление сервером
* Изменение настроек сервера в процессе игры без перезагрузки мира
* Сохранение настроек глобально или индивидуально для каждого мира
* Автоматическая загрузка сохраненных настроек при запуске мира
* Остановка сервера без выхода из мира

### Система туннелирования
* Доступ к вашему серверу извне локальной сети без необходимости настройки проброса портов
* Поддержка туннелирования через ngrok
* Автоматическое управление ключами ngrok через Auto-LAN Agent

## Хранение данных
* Глобальные настройки хранятся в файле `.minecraft/config/autolan.toml`
* Настройки для отдельных миров хранятся в `data/autolan.dat` в директории соответствующего мира

## Настройка туннелирования ngrok
Для использования функции туннелирования через ngrok:
1. Зарегистрируйтесь на [сайте ngrok](https://dashboard.ngrok.com/)
2. Получите ваш authtoken на странице [настроек](https://dashboard.ngrok.com/get-started/your-authtoken)
3. При первом запуске туннеля система автоматически запросит ключ через Auto-LAN Agent

## Требования
* Minecraft с поддержкой Fabric
* Fabric API

## Лицензия
Смотрите файл [LICENSE](LICENSE) для получения информации о лицензии.
