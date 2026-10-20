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
| `GET /internal/v1/integrations/{integrationId}/violation-groups/{groupId}` | Подробности группы нарушений | `200` |
| `GET /internal/v1/integrations/{integrationId}/summary` | Сводка сохраненных отчетов | `200` |

### OpenAPI и Swagger UI

После запуска откройте [Swagger UI](http://localhost:8080/swagger-ui.html).
Сгенерированная [OpenAPI 3.0.3](http://localhost:8080/v3/api-docs) содержит все шесть
бизнес-операций, их DTO, параметры, ошибки и синтетические примеры.
Если задан `MONITORING_PORT`, используйте его вместо 8080. Для запроса раскройте
операцию, нажмите `Try it out`, заполните параметры и нажмите `Execute`.

Спецификация строится из контроллеров и DTO через
[springdoc 3.1.1](https://springdoc.org/). Общая настройка дополняет правила UUID,
UTC, int64 и Problem Details; ошибки ControllerAdvice не добавляются автоматически.
Отдельного файла спецификации и генерации клиентов нет. Проверка credentials
в текущем Monitoring отсутствует; назначение пути как внутреннего не является защитой.

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
Обязательное `report.timestamp` содержит время формирования отчета SDK в ISO 8601
с `Z` или смещением, например `2026-10-20T13:00:00.1234567+03:00`.
Monitoring сохраняет его в `received_at` и возвращает как `receivedAt`:
`2026-10-20T10:00:00.123456Z`. Дробь входного времени допускает до девяти знаков;
знаки после микросекунд отбрасываются. Год после приведения к UTC: 0001-9999.
Отсутствие или null дает `400 VALIDATION_REPORT_INVALID`, неверный формат
или тип дает `400 INVALID_REQUEST`. Подстановки текущего времени нет.

Миграция V4 удаляет default колонки `received_at`. Ранее сохраненные даты остаются
прежними: восстановить время SDK для старых отчетов невозможно.

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

- `from` включает нижнюю границу сохраненного времени SDK, `to` исключает верхнюю.
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
firstSeenAt/lastSeenAt соответствуют min/max receivedAt связанных отчетов,
то есть времени их формирования SDK. Порядок доставки не меняет эти правила.
Счетчики передаются JSON-числами int64.

### Подробности группы

`GET /internal/v1/integrations/{integrationId}/violation-groups/{groupId}` возвращает
один объект `ViolationGroup` с теми же десятью полями, что элемент списка групп.
Пример с синтетическими данными:

```json
{
  "groupId": "77777777-7777-4777-8777-777777777777",
  "contractId": "33333333-3333-4333-8333-333333333333",
  "contractVersion": 3,
  "fieldName": "sample_field",
  "type": "TypeMismatch",
  "expectedType": "Number",
  "actualType": "String",
  "occurrenceCount": 3,
  "firstSeenAt": "2026-10-16T09:00:00.123456Z",
  "lastSeenAt": "2026-10-16T09:05:00Z"
}
```

Оба UUID проверяются до обращения к БД; ошибки собираются вместе в `errors`
по ключам `integrationId` и `groupId`. Регистр hex-букв любой, сокращения,
скобки и пробелы недопустимы. Нулевой UUID синтаксически допустим.
Query-параметров нет; неизвестные параметры игнорируются.

Группа ищется одним параметризованным SELECT по паре integrationId + groupId.
Неизвестная группа, чужая группа и неизвестная интеграция дают одинаковый
`404 VIOLATION_GROUP_NOT_FOUND`. groupId совпадает со ссылкой в подробностях
отчета. Для nullable groupId переход к группе не выполняется.

Возвращаются сохраненные значения, без пересчета счетчика и повторной проверки
отчета. occurrenceCount считает каждое появление, включая одинаковые элементы
одного отчета. expectedType остается metadata создания группы, включая
первоначальный null; metadata конкретного появления возвращает Task 7.
Два контракта одной интеграции с одинаковым номером версии имеют разные группы.

type: `RequiredFieldMissing`, `NullNotAllowed` или `TypeMismatch`.
expectedType/actualType: `String`, `Number`, `Boolean`, `Object`, `Array` или
явный `null`. Остальные поля обязательны. contractVersion имеет тип int32,
occurrenceCount положительный int64 и передается числом без кавычек.
Для точного разбора int64 выше 9007199254740991 в JavaScript нужен подходящий
парсер; обычный response.json() теряет точность. Даты возвращаются в UTC с `Z`,
с дробью до шести знаков; firstSeenAt <= lastSeenAt, равенство допустимо.

Один ответ содержит согласованное состояние строки на начало SELECT.
Незафиксированное обновление не видно, после commit следующий GET видит новые
значения. Список и подробности читаются отдельными запросами: между ними
новый отчет может изменить счетчик и время. Список отчетов группы не возвращается.

| HTTP / code | detail |
|---|---|
| `400 INVALID_REQUEST` | Некорректные идентификаторы группы нарушений. |
| `404 VIOLATION_GROUP_NOT_FOUND` | Группа нарушений не найдена в указанной интеграции. |
| `503 STORAGE_UNAVAILABLE` | Хранилище отчетов временно недоступно. |
| `500 INTERNAL_ERROR` | Не удалось прочитать группу нарушений. |

Для 400 сообщение каждого UUID: «Требуется UUID в стандартной строковой записи.».
Для остальных ошибок `errors={}`. Общий формат описан ниже.

### Сводка интеграции

`GET /internal/v1/integrations/{integrationId}/summary` возвращает `integrationId`,
`totalReports`, `passedReports`, `failedReports`, `lastReceivedAt`. Счетчики считают
сохраненные отчеты всех контрактов и версий за весь период; total равен сумме
Passed и Failed. Нарушения, группы и появления в эти счетчики не входят.

Один параметризованный SELECT читает общий снимок агрегатов без отдельной
транзакции приложения. `lastReceivedAt` равен MAX сохраненного receivedAt
(времени формирования SDK). Поздняя доставка более старого отчета не увеличивает
этот максимум. Duplicate не меняет сводку. Пустая или неизвестная
Monitoring интеграция получает `200`, нули и явный `lastReceivedAt:null`.
Существование интеграции в Core не проверяется. Query-параметров нет.

UUID проверяется до БД. Ошибки: `400 INVALID_REQUEST` с detail
«Некорректный идентификатор интеграции.», `503 STORAGE_UNAVAILABLE` либо
`500 INTERNAL_ERROR` с detail «Не удалось прочитать сводку интеграции.».
Техническая ошибка не заменяется нулями. Счетчики имеют полный диапазон int64;
правило точного разбора в JavaScript применимо и к сводке.

### Ошибки

Problem Details содержит `type`, `title`, `status`, `detail`, `instance`, `code`,
`errors`. `instance` содержит путь без query, `errors` является объектом
«поле -> массив сообщений» либо `{}` для общей ошибки.

| HTTP | code | Причина |
|---|---|---|
| `400` | `INVALID_REQUEST` | Некорректный JSON, UUID или параметры чтения |
| `400` | `VALIDATION_REPORT_INVALID` | Нарушение правил входного отчета |
| `404` | `REPORT_NOT_FOUND` | Отчет не найден в указанной интеграции |
| `404` | `VIOLATION_GROUP_NOT_FOUND` | Группа не найдена в указанной интеграции |
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
прием и дедупликация, транзакции, счетчики, история, подробности отчетов,
список и подробности групп, сводка и генерация OpenAPI/ресурсы Swagger UI.
Без тестовых переменных PostgreSQL-проверки пропускаются; количество пропусков
указано в `target/surefire-reports`.

Проверка от 20.10.2026: Maven Wrapper verify на JDK 21 с PostgreSQL 12.18 прошел,
61 тест, ошибок и пропусков нет. Проверены время из DTO, некорректные даты,
миграция старой схемы, обратный порядок доставки и неизменность данных при повторе.
Независимый валидатор подтвердил OpenAPI, все 36 примеров и реальные ответы шести
операций. Обновленные запросы и скрипты коллекции Postman проверены временным
Node runner на запущенном сервисе; сам интерфейс Postman не использовался.
Повторная проверка Core на коммите `151865f` подтвердила прием timestamp от SDK,
хранение в UTC и передачу сохраненного значения при повторах. На работающем
Monitoring проверены JSON из настоящих DTO Core с System.Text.Json и чтение ответов
типами Core: четыре формата точности, duplicate, все шесть операций, пустая сводка
и фильтр времени. DTO собраны в памяти отдельно от приложения Core.
Полный сценарий с SDK, БД/воркером Core и Frontend пока не запускался.

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
