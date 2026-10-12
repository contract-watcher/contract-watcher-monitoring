# Локальная проверка приема отчетов

Коллекция для импорта: [report-receiving.postman_collection.json](report-receiving.postman_collection.json).
Нужны JDK 21, запущенный PostgreSQL и Postman Desktop.
Core и SDK для этой проверки не требуются. Это проверка Monitoring отдельно.

## 1. Создать отдельную БД

Открой PowerShell. Команда ниже подключается к локальному PostgreSQL под ролью
администратора и попросит пароль. Если PostgreSQL использует другой порт,
замени 5432 на него.

```powershell
psql -h localhost -p 5432 -U postgres -d postgres
```

В открывшемся psql выполни по одной строке:

```sql
CREATE ROLE monitoring_manual_app LOGIN;
\password monitoring_manual_app
CREATE DATABASE monitoring_manual_task3 OWNER monitoring_manual_app;
\q
```

После `\password` придумай пароль для этой локальной роли и введи его дважды.
Пароль не помещается в SQL-команду или пример. LOGIN разрешает роли подключаться,
а OWNER делает ее владельцем новой БД. Приложение сможет создать собственную схему.
Если роль или БД с таким именем уже есть после прежней проверки, используй их
с известным тебе паролем либо выбери новые имена во всех следующих шагах.
Не удаляй существующую БД, чтобы освободить имя.

## 2. Запустить Monitoring

В новом окне PowerShell:

```powershell
cd C:\contract-watcher\contract-watcher-monitoring
java -version
.\mvnw.cmd --version
```

Обе команды должны показать Java 21. Maven отдельно устанавливать не нужно.

Затем настрой только текущий терминал, не меняя существующий `.env`:

```powershell
$env:MONITORING_DATABASE_URL = 'jdbc:postgresql://localhost:5432/monitoring_manual_task3'
$env:MONITORING_DATABASE_USER = 'monitoring_manual_app'
$taskPassword = Read-Host 'Пароль monitoring_manual_app' -AsSecureString
$env:MONITORING_DATABASE_PASSWORD = [Net.NetworkCredential]::new('', $taskPassword).Password
$env:MONITORING_PORT = '8080'
.\mvnw.cmd spring-boot:run
```

Строка URL является шаблоном для новой локальной БД из шага 1, а не рабочим
подключением. Настройки действуют только в этом терминале и имеют приоритет над `.env`.
Ввод пароля скрыт; его значение не печатай и не добавляй в Git.
Дождись сообщения `Started Application`. Flyway создаст схему `monitoring`
и применит V1/V2/V3. Окно оставь открытым: оно держит приложение запущенным.
Если порт 8080 занят, используй 8081 и поменяй baseUrl коллекции на
`http://localhost:8081`.

## 3. Импортировать коллекцию

1. Открой Postman Desktop и нажми Import.
2. Выбери файл `postman/report-receiving.postman_collection.json` из Monitoring.
3. Открой коллекцию «Monitoring: ручная проверка приема отчетов».
4. В Variables проверь `baseUrl`: `http://localhost:8080`.
5. Дополнительное Environment не выбирай, чтобы оно не заменяло переменные коллекции.
6. Запускай запросы 1–6 по порядку кнопкой Send. Authorization должен быть No Auth.
7. После каждого запроса смотри статус, тело ответа и Test Results.
8. Scripts -> Pre-request создает ID перед отправкой; Scripts -> Post-response
   проверяет ответ после отправки. Эти скрипты уже включены в коллекцию.

Можно выполнить всю коллекцию через Run с одной итерацией и тем же порядком запросов.
UUID первого и второго отчета меняются при каждом запуске, поэтому коллекцию
можно повторять. Третий запрос специально использует UUID первого отчета.

| Запрос | Что отправляется | Ожидаемый результат |
|---|---|---|
| 1. Passed | Отчет с пустым violations | 201, ID отчета и интеграции, receivedAt в UTC, duplicate=false |
| 2. Failed | Четыре нарушения, включая два одинаковых и nullable actualType | 201; все четыре элемента сохранены |
| 3. Повтор | reportId из запроса 1 | 409 REPORT_ID_CONFLICT |
| 4. Неправильный actualType | TypeMismatch, expectedType=Number, actualType=Number | 400 VALIDATION_REPORT_INVALID, указан путь actualType |
| 5. Поврежденный JSON | Одна открывающая фигурная скобка | 400 INVALID_REQUEST |
| 6. Неправильный Content-Type | Корректный JSON как text/plain | 415 UNSUPPORTED_MEDIA_TYPE |

## 4. Отправить запрос вручную

Если хочется разобраться без импорта: создай HTTP Request, выбери POST,
адрес `http://localhost:8080/internal/v1/reports`, Authorization -> No Auth,
Body -> raw -> JSON. Заголовок Content-Type должен быть application/json.

Пример Passed с вымышленными ID:

```json
{
  "integrationId": "22222222-2222-4222-8222-222222222222",
  "contractId": "33333333-3333-4333-8333-333333333333",
  "report": {
    "reportId": "77777777-7777-4777-8777-777777777777",
    "contractVersion": 3,
    "status": "Passed",
    "violations": []
  }
}
```

Первый запрос даст 201. Повтори ровно тот же запрос: теперь должен быть 409.
Для нового отчета нужен новый reportId. Если этот ID уже использовался раньше
в выбранной БД, первый запрос тоже получит 409; это ожидаемая защита от второй записи.

Для Failed используй другой reportId и следующий отчет:

```json
{
  "integrationId": "22222222-2222-4222-8222-222222222222",
  "contractId": "33333333-3333-4333-8333-333333333333",
  "report": {
    "reportId": "66666666-6666-4666-8666-666666666666",
    "contractVersion": 3,
    "status": "Failed",
    "violations": [
      {
        "fieldName": "customer_id",
        "type": "RequiredFieldMissing",
        "expectedType": "String",
        "actualType": null
      },
      {
        "fieldName": "amount",
        "type": "TypeMismatch",
        "expectedType": "Number",
        "actualType": "String"
      },
      {
        "fieldName": "amount",
        "type": "TypeMismatch",
        "expectedType": "Number",
        "actualType": "String"
      },
      {
        "fieldName": "optional",
        "type": "NullNotAllowed",
        "expectedType": "Boolean"
      }
    ]
  }
}
```

Ответ тоже должен быть 201: Failed означает обнаруженные SDK нарушения,
а не техническую ошибку Monitoring. В последнем элементе actualType отсутствует,
и в БД он сохранится как NULL.

Для проверки ошибки измени reportId на новый UUID, а у TypeMismatch замени
actualType на Number. Ожидается 400, потому что фактический тип совпал с ожидаемым.

## 5. Проверить строки в PostgreSQL

Подключись в другом терминале:

```powershell
psql -h localhost -p 5432 -U monitoring_manual_app -d monitoring_manual_task3
```

Пароль будет запрошен отдельно. Выполни:

```sql
SET TIME ZONE 'UTC';

SELECT external_report_id, integration_id, contract_id, contract_version,
       outcome, received_at
FROM monitoring.reports
ORDER BY report_row_id;

SELECT r.external_report_id, v.ordinal, v.field_name, v.violation_kind,
       v.expected_type, v.actual_type, v.group_id
FROM monitoring.report_violations v
JOIN monitoring.reports r USING (report_row_id)
ORDER BY r.report_row_id, v.ordinal;

SELECT count(*) FROM monitoring.violation_groups;
```

После одного запуска коллекции в новой БД должны быть два отчета, четыре нарушения
и ноль групп. Два одинаковых нарушения amount имеют ordinal 1 и 2.
У всех нарушений group_id пустой, у missing/null actual_type пустой: в psql
так отображается SQL NULL. Ошибочные запросы и повтор не добавляют строки.
Если дополнительно отправлялись ручные запросы или запускалась коллекция повторно,
отчетов будет больше; сравнивай по reportId.

Сравни received_at с receivedAt ответа того же отчета. Они обозначают одно время:
PostgreSQL может показать +00 вместо Z, это одинаковый часовой пояс UTC.

## 6. Что Postman не доказывает

Успех и ошибки 400/409/415 можно проверить обычными запросами. Отказ при поздней
вставке, ошибка commit и потеря соединения проверяются Java-тестами на PostgreSQL:
там используются временные triggers в собственной тестовой БД.
Не создавай такие triggers в БД обычного приложения.
Проверка Monitoring через Postman не подтверждает готовность настоящего пути из Core.

## 7. Остановить приложение

В терминале с Maven нажми Ctrl+C, затем закрой это окно PowerShell.
Введенный пароль и переменные процесса не записывались в `.env`.
Локальная БД остается для просмотра результатов. Flyway clean не используется.
