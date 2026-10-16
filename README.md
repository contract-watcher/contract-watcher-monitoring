![build](https://github.com/contract-watcher/contract-watcher-monitoring/actions/workflows/build.yml/badge.svg)
![release](https://github.com/contract-watcher/contract-watcher-monitoring/actions/workflows/release.yml/badge.svg)
![version](https://img.shields.io/github/v/tag/contract-watcher/contract-watcher-monitoring?sort=semver)

# ContractWatch Monitoring

Внутренний HTTP-сервис хранения результатов проверки контрактов. Принимает отчеты
от Core, хранит историю, объединяет нарушения в группы и считает их появления.
Данные находятся в схеме PostgreSQL `monitoring`. Доступ пользователей к интеграциям
проверяет Core.

## Требования

- JDK 21.
- PostgreSQL; интеграционные тесты проверены на версии 12.18.
- Maven Wrapper включен в репозиторий, отдельная установка Maven не требуется.

Стек: Spring Boot, Spring MVC, JDBC, Flyway, PostgreSQL.

## Конфигурация

| Переменная | Назначение | По умолчанию |
|---|---|---|
| `MONITORING_DATABASE_URL` | JDBC URL базы данных | Обязательна |
| `MONITORING_DATABASE_USER` | Роль PostgreSQL | Обязательна |
| `MONITORING_DATABASE_PASSWORD` | Пароль роли | Обязательна |
| `MONITORING_PORT` | HTTP-порт | `8080` |

Для локального запуска скопируйте `.env.example` в `.env` и заполните настройки БД.
Существующий `.env` перезаписывать не нужно. Файл читается из рабочего каталога
как properties: `ИМЯ=значение`, без кавычек и `export`. Переменные окружения имеют
приоритет. `.env` исключен из Git.

База должна существовать до запуска. Роли приложения нужны права на создание
схемы `monitoring` или владение подготовленной схемой. Flyway применяет миграции
при старте приложения; `clean` отключен.

## Запуск и сборка

Выполняйте команды из корня репозитория.

Windows:

```powershell
.\mvnw.cmd spring-boot:run
.\mvnw.cmd verify
```

Linux:

```bash
./mvnw spring-boot:run
./mvnw verify
```

В IDE точка входа: `contractwatcher.monitoring.Application`. Рабочий каталог:
корень репозитория. Для контейнерной сборки используется [Dockerfile](Dockerfile);
конфигурация передается переменными окружения.

## HTTP API

| Метод и путь | Назначение | Успешный ответ |
|---|---|---|
| `POST /internal/v1/reports` | Сохранить отчет и обновить группы нарушений | `201`, повтор `200` |
| `GET /internal/v1/integrations/{integrationId}/reports` | История проверок | `200` |
| `GET /internal/v1/integrations/{integrationId}/reports/{reportId}` | Подробности отчета с нарушениями | `200` |
| `GET /internal/v1/integrations/{integrationId}/violation-groups` | Список групп нарушений | `200` |

Запросы и ответы используют JSON. Успешный ответ: `application/json`, ошибка:
`application/problem+json`. UUID передаются строками в записи 8-4-4-4-12 hex;
время ответа имеет UTC и окончание `Z`. Nullable-поля присутствуют явно как `null`,
пустые массивы как `[]`. Enum передаются строками с учетом регистра.

Контракты и JSON-примеры находятся в Common:

- [Прием отчетов](../contract-watcher-common/examples/monitoring/reports/README.md).
- [История проверок](../contract-watcher-common/examples/monitoring/history/README.md).
- [Подробности отчета](../contract-watcher-common/examples/monitoring/details/README.md).
- [Список групп нарушений](../contract-watcher-common/examples/monitoring/violation-groups/README.md).
- [Формат ошибок](../contract-watcher-common/examples/monitoring/errors/README.md).

### Прием отчетов

Тело POST содержит `integrationId`, `contractId` и `report`. Новый отчет, его
нарушения и счетчики групп сохраняются одной транзакцией. Ответ выдается после
commit и содержит `integrationId`, `reportId`, `receivedAt`, `duplicate`.
Время `receivedAt` задается PostgreSQL при приеме.

Дедупликация выполняется по паре `integrationId + reportId`. Валидный повтор
возвращает `200`, `duplicate=true` и прежнее `receivedAt`; содержимое первого
отчета и счетчики не меняются. Некорректный повтор проходит обычную валидацию.
Тот же reportId в другой интеграции считается отдельным отчетом.

### История и пагинация

Оба endpoint'а списков возвращают `items`, `total`, `offset`, `limit`.

| Параметр | По умолчанию | Диапазон |
|---|---|---|
| `offset` | `0` | `0..2147483647` |
| `limit` | `20` | `1..100` |

`limit` ограничивает число элементов одного ответа. Общее число доступных
элементов находится в `total`. Например, `offset=0&limit=100` выбирает первые
100 строк, `offset=100&limit=100` следующие, `offset=200&limit=100` еще 100.
Просмотр продолжается, пока offset меньше total. Значение limit не ограничивает total.

Значения offset/limit содержат только десятичные цифры, без знака и пробелов;
ведущие нули допустимы. Известный параметр передается один раз и непустым.
Неизвестные query-параметры игнорируются. Пустая или неизвестная интеграция дает
`items=[]`, `total=0`. Страница за пределами результата сохраняет настоящий total.

История дополнительно принимает `from`, `to`, `status`:

- `from` включает нижнюю границу времени приема, `to` исключает верхнюю.
- Формат времени: `YYYY-MM-DDTHH:mm:ss[.ffffff]Z`, год 0001-9999, дробь до шести
  знаков; при двух границах требуется `from < to`.
- `status`: `Passed` или `Failed`, без параметра оба результата.

Фильтры истории применяются до пагинации. Порядок истории:
`received_at DESC, report_row_id DESC`. Порядок групп:
`last_seen_at DESC, group_id DESC`. У списка групп дополнительных фильтров нет.

Страницы стабильны для неизмененных данных. Новые отчеты могут сдвинуть историю
или переместить группу, поэтому между запросами возможны повторы или пропуски.
При обновлении списка начинайте с offset=0.

### Группы нарушений

Ключ группы: `integrationId + contractId + contractVersion + fieldName + type + actualType`.
Имена полей сравниваются точно, включая пробелы и регистр. Отсутствующий actualType
учитывается как null. Версия относится к конкретному contractId.

Каждый элемент violations нового отчета считается отдельным появлением, включая
одинаковые элементы. expectedType не входит в ключ: группа хранит значение
создавшего ее появления, подробности отчета сохраняют metadata каждого элемента.
firstSeenAt/lastSeenAt соответствуют min/max receivedAt связанных отчетов.
Счетчики передаются JSON-числами int64.

### Ошибки

Problem Details содержит `type`, `title`, `status`, `detail`, `instance`, `code`,
`errors`. `instance` содержит путь без query, `errors` является объектом
«поле -> массив сообщений» либо `{}` для общей ошибки.

| HTTP | code | Причина |
|---|---|---|
| `400` | `INVALID_REQUEST` | Некорректный JSON, UUID или параметры чтения |
| `400` | `VALIDATION_REPORT_INVALID` | Нарушение правил входного отчета |
| `404` | `REPORT_NOT_FOUND` | Отчет не найден в указанной интеграции |
| `415` | `UNSUPPORTED_MEDIA_TYPE` | Неподдерживаемый Content-Type входного отчета |
| `503` | `STORAGE_UNAVAILABLE` | Хранилище временно недоступно |
| `500` | `INTERNAL_ERROR` | Ошибка обработки или чтения |

## Тестирование

`verify` выполняет компиляцию и тесты. Предупреждения компилятора считаются ошибками.
Проверки валидации и маппинга работают без БД. Для HTTP/PostgreSQL-тестов задайте:

| Переменная | Назначение |
|---|---|
| `MONITORING_TEST_DATABASE_URL` | JDBC URL отдельной БД `monitoring_test_...` |
| `MONITORING_TEST_DATABASE_USER` | Тестовая роль с правом `CREATEDB` |
| `MONITORING_TEST_DATABASE_PASSWORD` | Пароль тестовой роли |

Тесты запуска используют исходную тестовую БД. Тесты хранения и HTTP создают
собственные временные БД `monitoring_test_storage_...` и
`monitoring_test_ingestion_...` и удаляют их после выполнения. Проверяются миграции,
прием и дедупликация, транзакции, счетчики, история, подробности и список групп.
Без тестовых переменных PostgreSQL-проверки пропускаются; количество пропусков
указано в `target/surefire-reports`.

Для ручных запросов: [коллекция приема отчетов](postman/report-receiving.postman_collection.json)
и [инструкция Postman](postman/README.md). Примеры остальных запросов находятся в Common.

## Структура проекта

| Каталог | Содержимое |
|---|---|
| `src/main/java/contractwatcher/monitoring/controller` | HTTP endpoints и обработчик ошибок |
| `src/main/java/contractwatcher/monitoring/dto` | HTTP-модели |
| `src/main/java/contractwatcher/monitoring/service` | Валидация, запись и чтение данных |
| `src/main/java/contractwatcher/monitoring/repository` | JDBC-запросы |
| `src/main/java/contractwatcher/monitoring/model` | Внутренние модели |
| `src/main/java/contractwatcher/monitoring/mapper` | Преобразование входных данных |
| `src/main/java/contractwatcher/monitoring/config` | Конфигурация JSON |
| `src/main/java/contractwatcher/monitoring/exception` | Исключения приложения |
| `src/main/resources/db/migration` | Миграции Flyway |
| `src/test` | Тесты |
