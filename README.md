# КТ 4 — Ktor Realtime API

Отдельный учебный проект на Kotlin: REST API с PostgreSQL, JWT, ролями `admin` и `user`, чатом и персональными оповещениями через WebSocket. В приложении есть браузерный интерфейс для демонстрации и Swagger UI.

Стек: Kotlin 2.2.10, Ktor 3.2.3 / Netty, PostgreSQL 17, JDK 17, Maven Wrapper 3.9.12, Docker Compose. Исполняемый JAR: `target/ktor-realtime-api.jar`, главный класс: `ru.student.realtime.MainKt`.

| Требование КТ 4 | Реализация |
| --- | --- |
| API с Ktor, PostgreSQL, JWT и Swagger | REST API, постоянное хранение, bearer JWT, `/swagger`, `/openapi.yaml` |
| Роли admin и user | Регистрация только `user`, создание начального `admin`, защита административных маршрутов |
| WebSocket: чат и оповещения | `/ws`, JWT первым кадром, сохранение истории в БД |
| Docker | Многостадийный Dockerfile, отдельный PostgreSQL, volume, health checks |
| Git и документация | `.gitignore`, инструкции запуска и публикации, OpenAPI, запросы HTTP Client, CI |

## Быстрый запуск в Docker

Нужен запущенный Docker Desktop с Linux containers. Команды выполняются из каталога проекта.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Init-Env.ps1
docker compose up --build -d
docker compose ps
docker compose logs --tail 100 app
```

Linux/macOS: вместо первой команды выполните `bash scripts/init-env.sh`; остальные команды те же. Для генерации секретов в Bash требуется `openssl`. Скрипты не перезаписывают существующий `.env`.

Откройте [интерфейс приложения](http://localhost:8091/), [Swagger UI](http://localhost:8091/swagger) или [проверку готовности](http://localhost:8091/health/ready). Порты по умолчанию: приложение `8091`, PostgreSQL `55435`. Внешние порты можно изменить в `.env` через `APP_PORT` и `PG_PORT`. Порты контейнеров публикуются на `127.0.0.1`.

Начальный администратор: имя из `ADMIN_USERNAME` и случайный пароль из `ADMIN_PASSWORD` в локальном `.env`. Пароль не выводится в логах. Регистрация создаёт пользователей с ролью `user`. Данные сохраняются при `docker compose down` и следующем запуске. Команда `docker compose down -v` **удаляет БД и всю историю**; применяйте её только для осознанного сброса учебных данных.

## Открытие отдельным проектом в IntelliJ IDEA

1. Выберите **File → Open** и каталог `KtorRealtimeApi` либо его `pom.xml`, затем **Open as Project / New Window**.
2. Укажите **Project SDK = JDK 17**, дождитесь импорта Maven. Maven JVM также должен быть JDK 17.
3. Встроенный **Terminal** откроется в корне нового проекта. Создайте `.env` командой `Init-Env.ps1` выше.
4. Запустите только БД: `docker compose up -d postgres`.
5. Выполните `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Run-Local.ps1`. Скрипт читает `.env`, задаёт `DATABASE_*`, `JWT_SECRET`, `ADMIN_*`, `PORT`, компилирует проект и запускает Ktor. Остановка — `Ctrl+C`.

На Linux/macOS пятый шаг: `bash scripts/run-local.sh`. При запуске из IDEA приложение работает на машине, а PostgreSQL — в Docker. Перед локальным запуском остановите контейнер приложения командой `docker compose stop app`, если он уже занимает порт. Конфигурация **Verify** в списке Run Configurations запускает Maven `verify`; секреты в общих настройках IDEA не хранятся. Для отладки `MainKt` можно создать локальную конфигурацию Kotlin/Application и указать переменные из [конфигурации](docs/CONFIGURATION.md).

Если Docker Desktop не запускается, можно использовать установленный PostgreSQL 17 без Docker:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Init-Env.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-Database.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Run-Local.ps1
```

`Start-Database.ps1` создаёт отдельный локальный кластер в `.local/postgres`, берёт порт и секреты из `.env` и не использует данные других проектов. Если PostgreSQL установлен в другом месте, передайте `-PostgresBin 'C:\Program Files\PostgreSQL\17\bin'`. Для остановки этого кластера выполните `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Stop-Database.ps1`. Локальная БД и Docker-БД — разные хранилища: не запускайте их одновременно на одном порту.

## Демонстрация КТ 4

1. В двух окнах браузера откройте `http://localhost:8091/`. Создайте разных пользователей; войдите каждым из них.
2. Подключитесь к чату и отправьте сообщения: они появляются у подключённых пользователей и доступны через `GET /api/messages` после обновления страницы.
3. В отдельной сессии войдите как `admin`. Получите список пользователей и создайте адресное или общее оповещение. У получателей оно приходит через WebSocket и сохраняется в `/api/notifications`.
4. Отметьте оповещение прочитанным. Проверьте, что другой пользователь не может изменить чужое оповещение.
5. Откройте `/swagger`, войдите через `/api/auth/login`, скопируйте `accessToken` в **Authorize**. Введите сам токен, без префикса `Bearer`. Покажите разрешённый запрос и `403` для административного запроса с токеном пользователя.
6. Перезапустите приложение командой `docker compose restart app`: история останется в PostgreSQL.

WebSocket использует первое сообщение `{"type":"auth","token":"<JWT>"}` в течение 5 секунд после соединения. JWT в URL не передаётся. Полный протокол и пример для консоли браузера: [docs/API.md](docs/API.md).

## Основные маршруты

| Метод и путь | Доступ | Назначение |
| --- | --- | --- |
| `GET /`, `/swagger`, `/openapi.yaml` | Публичный | Интерфейс и документация |
| `GET /health/live`, `/health/ready` | Публичный | Работа процесса и доступность БД |
| `POST /api/auth/register`, `/api/auth/login` | Публичный | Регистрация / вход, получение JWT |
| `GET /api/users/me` | user/admin | Текущий пользователь |
| `GET`, `POST /api/messages` | user/admin | История чата / новое сообщение |
| `GET /api/notifications` | user/admin | Собственные оповещения |
| `PATCH /api/notifications/{id}/read` | Владелец | Пометка оповещения прочитанным |
| `GET /api/admin/users` | admin | Пользователи с пагинацией |
| `PATCH /api/admin/users/{id}/role` | admin | Изменение роли; нельзя снять собственную роль admin |
| `POST /api/admin/notifications` | admin | Адресное / общее оповещение |
| `WS /ws` | JWT в первом кадре | Чат и личные оповещения в одном соединении |

Тела запросов и ответов, ограничения и коды ошибок описаны в [OpenAPI](src/main/resources/openapi/documentation.yaml) и [docs/API.md](docs/API.md). Готовые запросы для IntelliJ HTTP Client находятся в [requests.http](requests.http).

## Проверка и сборка

```powershell
.\mvnw.cmd -B verify
.\mvnw.cmd -DskipTests package
docker compose config --quiet
```

Linux/macOS: `bash ./mvnw -B verify`. Для включения теста с реальным PostgreSQL выполните `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Test-Postgres.ps1` при запущенной БД.

Локально 23.10.2026 успешно прошли **14 тестов, включая PostgreSQL-интеграцию: 0 ошибок, 0 пропусков**. Проверена конфигурация Compose; Docker runtime локально не проверен из-за сбоя установленного Docker Desktop Secrets Engine (`engine.sock`). **Docker проверен в [успешном запуске GitHub Actions](https://github.com/samurai3452345/KtorRealtimeApi/actions/runs/39919586941):** тесты с PostgreSQL, сборка образа, запуск Compose до состояния healthy и HTTP-проверки `/health/ready`, `/swagger`, `/openapi.yaml`. Браузерная проверка охватила чат двух пользователей, роли, приватность уведомлений и размеры экрана 360/390/720/960/1440 px. Подробнее: [docs/TESTING.md](docs/TESTING.md).

## Репозиторий Git

Репозиторий проекта: [samurai3452345/KtorRealtimeApi](https://github.com/samurai3452345/KtorRealtimeApi) (закрытый; требуется доступ к аккаунту или приглашение).

Для получения проекта на другой машине:

```powershell
git clone https://github.com/samurai3452345/KtorRealtimeApi.git
cd KtorRealtimeApi
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Init-Env.ps1
```

Секреты и данные не хранятся в Git: на новой машине создайте собственный `.env` и запустите приложение по инструкции выше. Перед следующим commit проверьте `git status --short` и `git check-ignore .env`, чтобы `.env`, `.local/`, `target/` и `.idea/` не попали в индекс. Workflow проверок доступен на вкладке [Actions](https://github.com/samurai3452345/KtorRealtimeApi/actions).

Дополнительно: [архитектура](docs/ARCHITECTURE.md), [конфигурация и секреты](docs/CONFIGURATION.md), [проверки и устранение ошибок](docs/TESTING.md).

Официальная документация: [Ktor JWT](https://ktor.io/docs/server-jwt.html), [Ktor WebSockets](https://ktor.io/docs/server-websockets.html), [Ktor Swagger UI](https://ktor.io/docs/server-swagger-ui.html), [PostgreSQL](https://www.postgresql.org/docs/17/), [PostgreSQL Docker image](https://hub.docker.com/_/postgres), [Docker Compose](https://docs.docker.com/compose/). В этом проекте Swagger UI поставляется локальным WebJar, поэтому браузеру не требуется CDN.
