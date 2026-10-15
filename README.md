![build](https://github.com/contract-watcher/contract-watcher-monitoring/actions/workflows/build.yml/badge.svg)
![release](https://github.com/contract-watcher/contract-watcher-monitoring/actions/workflows/release.yml/badge.svg)
![version](https://img.shields.io/github/v/tag/contract-watcher/contract-watcher-monitoring?sort=semver)

# Monitoring

Код организован по слоям внутри `contractwatcher.monitoring`:

- `controller`: HTTP endpoints приема и истории, обработчик Problem Details.
- `dto`: входной конверт, отчет, нарушения, подтверждение приема и страница истории.
- `service`: валидация, прием и чтение истории в отдельных транзакциях.
- `mapper`: преобразование проверенного DTO в данные для хранения.
- `model`: внутренние данные записи, сохранения и чтения отчетов.
- `repository`: JDBC-запросы к отчетам, нарушениям и группам.
- `exception`: ошибки валидации отчета и параметров истории.
- `config`: строгая настройка JSON и десериализация enum.

Путь запроса: `ReportIngestionController -> ReportIngestionService -> ReportValidator`,
затем `ReportMapper` и три репозитория внутри одной транзакции. Ответ формирует
сервис после сохранения; контроллер выбирает 201 или 200. Репозитории работают
с внутренней моделью и не зависят от HTTP DTO. Используется JDBC, JPA-сущностей нет.
Для текущего размера достаточно пакетов по слоям; новые функции добавляются
в соответствующий слой без дополнительных интерфейсов и архитектурных оболочек.

Для запуска нужны JDK 21 и запущенный PostgreSQL с подготовленной базой.
Роли приложения нужны права на создание схемы monitoring или владение уже созданной схемой.

Один раз скопируйте `.env.example` в `.env` и заполните настройки своей БД.
Если `.env` уже существует, используйте его.

```text
MONITORING_DATABASE_URL=jdbc:postgresql://localhost:5432/contractwatch_monitoring_local
MONITORING_DATABASE_USER=contractwatch_monitoring_local_app
MONITORING_DATABASE_PASSWORD=<пароль роли PostgreSQL>
MONITORING_PORT=8080
```

Spring Boot автоматически читает `.env` из рабочего каталога как файл properties:
строки `ИМЯ=значение`, без кавычек и `export`. `.env` исключен из Git.
Настоящие environment variables имеют приоритет над значениями из файла.

Запустите `contractwatcher.monitoring.Application` из IDE с рабочим каталогом
в корне Monitoring или выполните из этого каталога:

```powershell
.\mvnw.cmd spring-boot:run
```

При старте Flyway автоматически создает схему monitoring и применяет миграции.
В CI и контейнерах `.env` не нужен: настройки передаются через environment variables.

Сборка:

```powershell
.\mvnw.cmd verify
```

Тесты PostgreSQL запускаются при наличии `MONITORING_TEST_DATABASE_URL`,
`MONITORING_TEST_DATABASE_USER` и `MONITORING_TEST_DATABASE_PASSWORD` в окружении.
Используйте отдельную тестовую БД с именем `monitoring_test_...`, без рабочих данных.
Тестам хранения и приема нужна роль с правом `CREATEDB`: они создают собственные
временные БД `monitoring_test_storage_<случайный ID>` и
`monitoring_test_ingestion_<случайный ID>` и удаляют только их после проверки.
Исходную тестовую БД используют тесты запуска приложения; Flyway создает там схему
`monitoring`. `Flyway clean` не вызывается. Отдельные SQL-fixtures откатываются.

Проверяются чистая БД, обновление V1 -> V2/V3, повторные миграции, сохранение отчетов
и нарушений, nullable metadata, ограничения и уникальность групп и отчетов.
HTTP-тесты приема проверяют строгий JSON и metadata, сохранение всех нарушений,
повтор `reportId`, конкурентную доставку, создание и обновление групп, rollback
при поздней вставке и commit, потерю соединения,
а также совпадение времени подтверждения с БД. Тестовые triggers создаются только
в собственной временной БД; HTTP-запросы выполняют настоящий commit приложения.
Проверки подробностей используют тот же стенд: полный ответ и порядок нарушений,
изоляция пары ID, ошибки UUID и отсутствие записи до commit.
Проверки маппинга и валидации работают без БД. Если тестовые переменные не заданы,
тесты PostgreSQL пропускаются. Успешная сборка с такими
пропусками не подтверждает работу хранения на PostgreSQL.

`POST /internal/v1/reports` принимает конверт `integrationId`, `contractId`, `report`.
После проверки отчет, все нарушения и изменения групп сохраняются одной транзакцией. Ответ 201
содержит `integrationId`, `reportId`, `receivedAt` из PostgreSQL в UTC и
`duplicate=false`. Одинаковые нарушения сохраняются отдельно с исходным ordinal
и общим group_id; каждый элемент увеличивает occurrence_count на 1.
Ключ группы: integrationId + contractId + contractVersion + fieldName + violationKind + actualType.
Имена полей сравниваются точно, NULL actualType образует одну идентичность.
expectedType не входит в ключ: группа хранит снимок при создании, каждое
нарушение сохраняет собственный ожидаемый тип. UUID группы создает Monitoring.
Два варианта SQL UPSERT используют существующие partial unique индексы для NULL
и непустого actualType. PostgreSQL атомарно увеличивает счетчик и возвращает group_id.
first_seen_at и last_seen_at равны минимуму/максимуму receivedAt связанных отчетов.
Группы обрабатываются в едином порядке ключей, даже если нарушения пришли в другом
порядке. Ошибка записи или commit откатывает отчет, нарушения и все изменения групп.
Старые FAILED с group_id=NULL остаются без пересчета; duplicate их не привязывает.
Их появления пока не входят в группы, для восстановления нужна отдельная работа.
Ошибки возвращаются как Problem Details с `code` и объектом `errors`.
Валидный повтор пары `integrationId + reportId` получает 200 с `duplicate=true`
и прежним `receivedAt` в UTC. Первый успешно сохраненный отчет окончателен:
другое допустимое содержимое или `contractId` не меняет отчет, нарушения и счетчики.
Тот же `reportId` в другой интеграции можно сохранить как новый отчет.
При конкуренции PostgreSQL ожидает исход первой вставки. После ее commit повтор
читает прежнее подтверждение отдельным SELECT в READ COMMITTED; после rollback
ожидавший запрос может сохранить свой отчет. Ответ выдается после commit.
Некорректный повтор получает прежние 400/415, технические ошибки остаются 500/503.

Для ручной проверки в Postman есть готовая
[коллекция](postman/report-receiving.postman_collection.json).
Запустите Monitoring на отдельной локальной БД и импортируйте файл в Postman.
В переменных коллекции задайте `baseUrl` (по умолчанию `http://localhost:8080`).
Запускайте запросы по порядку: Passed, Failed, повтор reportId, неправильный actualType,
поврежденный JSON и неправильный Content-Type. Новые ID создаются автоматически;
запрос на повтор использует ID Failed. В Scripts -> Post-response находятся
проверки ответов. Подробные шаги: [локальная проверка](postman/README.md).

## История проверок: контракт для Core

`GET /internal/v1/integrations/{integrationId}/reports` возвращает HTTP 200,
`application/json`. `integrationId` обязателен и имеет стандартную запись UUID.
Core проверяет существование интеграции и доступ к ней перед внутренним запросом.

Ответ: `items`, `total`, `offset`, `limit`. Строка `items` содержит только:

| Поле | Тип и смысл |
|---|---|
| `reportId` | UUID отчета из принятого конверта |
| `contractId` | UUID самостоятельного контракта |
| `contractVersion` | Положительное целое, версия внутри контракта |
| `status` | Точная строка `Passed` или `Failed`, как в Common |
| `receivedAt` | Сохраненное серверное время приема, UTC с `Z` |
| `violationCount` | Целое 64 бит, число сохраненных нарушений, включая повторяющиеся и старые без группы |

`total` является целым 64 бит и считает отчеты после фильтров, до пагинации.
Внутренний ID строки и подробности нарушений в ответ не входят.

| Необязательный параметр | Правила |
|---|---|
| `from` | `YYYY-MM-DDTHH:mm:ss[.ffffff]Z`, нижняя граница включительно |
| `to` | Тот же формат, верхняя граница исключительно |
| `status` | Только `Passed` или `Failed`; отсутствие выбирает оба |
| `offset` | Целое без знака и пробелов, default 0, диапазон 0..2147483647 |
| `limit` | Целое без знака и пробелов, default 20, диапазон 1..100 |

Даты должны быть календарно корректными, с годом 0001-9999, секундами 00-59,
дробью от 1 до 6 знаков либо без дроби. Смещения вместо `Z` не принимаются.
Одна граница периода допустима; при двух требуется `from < to`.
Известные параметры передаются один раз, непустыми; значения не нормализуются.
Неизвестные параметры игнорируются и не добавляют фильтров.

Без фильтров доступна вся сохраненная история интеграции. Для последовательного
просмотра запросите `offset=0&limit=20`, затем увеличивайте offset на limit,
пока не достигнете total. При смене фильтров начинайте с offset=0.
Порядок: `received_at DESC, report_row_id DESC`, включая равное время.
Для неизменного набора страницы стабильны; новый commit между запросами может
сдвинуть offset и вызвать повтор или пропуск. Снимок между HTTP-запросами не сохраняется.

Все фильтры выполняются в PostgreSQL до LIMIT/OFFSET. `items`, `total` и счетчики
нарушений читаются в одном снимке отдельной read-only транзакции REPEATABLE READ.
Время приема по-прежнему задается БД в начале сохраняющей транзакции.

Пустая или неизвестная интеграция и фильтр без совпадений дают `items=[]`, `total=0`.
Страница за пределами результата дает `items=[]` с действительным total.
Во всех случаях сохраняются запрошенные offset/limit, HTTP status 200.

Ошибки используют существующий Problem Details (`application/problem+json`):
`type=about:blank`, `title`, `status`, `detail`, `instance`, `code`, `errors`.
`instance` содержит путь запроса без query string, `errors` является объектом
с массивами сообщений по именам параметров, без отклоненных значений.

- 400 `INVALID_REQUEST`: неверный UUID, пустые, повторенные или некорректные
  известные параметры; ошибка `from >= to` относится к `to`.
- 503 `STORAGE_UNAVAILABLE`: хранилище временно недоступно, `errors={}`.
- 500 `INTERNAL_ERROR`: прочая техническая ошибка, `errors={}`.

Core передает фильтры в Monitoring и разбирает эту оболочку. Техническую ошибку
нельзя выдавать Frontend как пустую историю. Подключение Core/Frontend и проверка
полного маршрута выполняются отдельно; это описание подготовлено для передачи.

## Подробности отчета: контракт для Core

`GET /internal/v1/integrations/{integrationId}/reports/{reportId}` возвращает
HTTP 200, `application/json`. Оба параметра имеют стандартную запись UUID
8-4-4-4-12 hex, регистр hex допускается любой. Core проверяет доступ к интеграции
и передает оба ID. Monitoring ищет строго эту пару в своей схеме, без обращения к Core.

Ответ плоский: `integrationId`, `reportId`, `contractId`, `contractVersion`,
`status`, `receivedAt`, `violationCount`, `violations`. Заголовок совпадает с историей:
UUID и версия сохраненного контракта, `Passed`/`Failed`, время приема в UTC с `Z`,
64-битное число нарушений. Внутренний ID строки в ответ не входит.

Каждое нарушение содержит `groupId`, `fieldName`, `type`, `expectedType`, `actualType`.
`groupId` берется из сохраненного нарушения, а имя поля возвращается точно, включая пробелы.
`type`: `RequiredFieldMissing`, `NullNotAllowed`, `TypeMismatch`.
Типы: `String`, `Number`, `Boolean`, `Object`, `Array`, как в Common.
Nullable `groupId`, `expectedType`, `actualType` присутствуют явно со значением `null`.
Ожидаемый тип читается из нарушения, а не из снимка группы.

`violations` содержит все сохраненные элементы по `ordinal ASC`, включая одинаковые.
Для Passed возвращается `[]` и `violationCount=0`. Счетчик равен размеру списка.
GET не запускает повторную валидацию, не нормализует metadata и не меняет группы.
Заголовок и список читает один параметризованный LEFT JOIN, используя существующие
ключи `(integration_id, external_report_id)` и `(report_row_id, ordinal)`.
Один SELECT PostgreSQL читает общий снимок обеих таблиц в READ COMMITTED;
отдельная REPEATABLE READ транзакция истории здесь не нужна.
До commit приема GET может вернуть 404; после commit возвращается полный отчет.
Ожидание незавершенного приема и повтор запроса внутри GET не добавляются.

Примеры содержат вымышленные ID и имена полей, без значений webhook.
Failed с двумя одинаковыми нарушениями одной группы:

```json
{
  "integrationId": "22222222-2222-4222-8222-222222222222",
  "reportId": "66666666-6666-4666-8666-666666666666",
  "contractId": "33333333-3333-4333-8333-333333333333",
  "contractVersion": 3,
  "status": "Failed",
  "receivedAt": "2026-10-13T09:00:00.123456Z",
  "violationCount": 3,
  "violations": [
    {
      "groupId": "77777777-7777-4777-8777-777777777777",
      "fieldName": "field_b",
      "type": "TypeMismatch",
      "expectedType": "Number",
      "actualType": "String"
    },
    {
      "groupId": "88888888-8888-4888-8888-888888888888",
      "fieldName": "field_a",
      "type": "RequiredFieldMissing",
      "expectedType": "String",
      "actualType": null
    },
    {
      "groupId": "77777777-7777-4777-8777-777777777777",
      "fieldName": "field_b",
      "type": "TypeMismatch",
      "expectedType": "Number",
      "actualType": "String"
    }
  ]
}
```

Passed:

```json
{
  "integrationId": "22222222-2222-4222-8222-222222222222",
  "reportId": "99999999-9999-4999-8999-999999999999",
  "contractId": "33333333-3333-4333-8333-333333333333",
  "contractVersion": 3,
  "status": "Passed",
  "receivedAt": "2026-10-13T09:01:00Z",
  "violationCount": 0,
  "violations": []
}
```

Ошибки используют тот же Problem Details, что прием и история:

- 400 `INVALID_REQUEST`: неверный UUID; `errors.integrationId` и/или `errors.reportId`
  содержат массивы сообщений. Обе ошибки собираются до запроса к БД,
  отклоненные значения не включаются в `detail`/`errors` и логи.
- 404 `REPORT_NOT_FOUND`: неизвестная пара, в том числе отчет чужой интеграции.
  Оба случая одинаковы; `errors={}`. Monitoring не проверяет существование интеграции.
- 503 `STORAGE_UNAVAILABLE`: хранилище временно недоступно, `errors={}`.
- 500 `INTERNAL_ERROR`: прочая ошибка чтения, `errors={}`.

404, `application/problem+json`:

```json
{
  "type": "about:blank",
  "title": "Not Found",
  "status": 404,
  "detail": "Отчет не найден в указанной интеграции.",
  "instance": "/internal/v1/integrations/22222222-2222-4222-8222-222222222222/reports/66666666-6666-4666-8666-666666666666",
  "code": "REPORT_NOT_FOUND",
  "errors": {}
}
```

`instance` содержит фактический путь без query, в логах используется шаблон маршрута.
Core должен разбирать плоский DTO, UUID/null группы и объект `errors`, сохранять
порядок и повторы и передавать результат Frontend. Техническая ошибка не означает
пустой отчет. Групповой API реализуется отдельно. Локальные HTTP/PostgreSQL-проверки
этого GET не подтверждают реальный маршрут Core -> Monitoring -> Frontend.
