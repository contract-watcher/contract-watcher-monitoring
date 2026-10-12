![build](https://github.com/contract-watcher/contract-watcher-monitoring/actions/workflows/build.yml/badge.svg)
![release](https://github.com/contract-watcher/contract-watcher-monitoring/actions/workflows/release.yml/badge.svg)
![version](https://img.shields.io/github/v/tag/contract-watcher/contract-watcher-monitoring?sort=semver)

# Monitoring

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
повтор `reportId`, конкурентную доставку, rollback при поздней вставке и commit, потерю соединения,
а также совпадение времени подтверждения с БД. Тестовые triggers создаются только
в собственной временной БД; HTTP-запросы выполняют настоящий commit приложения.
Если тестовые переменные не заданы, тесты пропускаются. Успешная сборка с такими
пропусками не подтверждает работу хранения на PostgreSQL.

`POST /internal/v1/reports` принимает конверт `integrationId`, `contractId`, `report`.
После проверки отчет и все нарушения сохраняются одной транзакцией. Ответ 201
содержит `integrationId`, `reportId`, `receivedAt` из PostgreSQL в UTC и
`duplicate=false`. Одинаковые нарушения сохраняются отдельно; группы пока не заполняются.
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
запрос на повтор использует ID первого запроса. В Scripts -> Post-response находятся
проверки ответов. Подробные шаги: [локальная проверка](postman/README.md).
