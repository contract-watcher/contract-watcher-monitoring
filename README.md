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
Тестам хранения нужна роль с правом `CREATEDB`: они создают собственные временные
БД `monitoring_test_storage_<случайный ID>` и удаляют только их после проверки.
Исходную тестовую БД используют тесты запуска приложения; Flyway создает там схему
`monitoring`. `Flyway clean` не вызывается. Отдельные SQL-fixtures откатываются.

Проверяются чистая БД, обновление V1 -> V2/V3, повторные миграции, сохранение отчетов
и нарушений, nullable metadata, ограничения и уникальность групп и отчетов.
Если тестовые переменные не заданы, тесты пропускаются. Успешная сборка с такими
пропусками не подтверждает работу хранения на PostgreSQL.
