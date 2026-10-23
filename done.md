# Журнал выполненной работы

Источники: `BANKING_API_RESEARCH_SPEC.md` (ТЗ), `BANKING_API_IMPLEMENTATION_STAGES.md` (этапы). Дата: 2026-10-23.

## Этап 0. Проверка исходного проекта — выполнен

**Осмотр.**
- Исходные файлы: `pom.xml`, `application.properties` (одна строка `spring.application.name`), `BankigServiceScientificApplication.java`, `BankigServiceScientificApplicationTests.java` (`contextLoads`), Maven Wrapper (`mvnw`, `mvnw.cmd`, `.mvn/wrapper`), `.gitignore`, `.gitattributes`.
- Git: ветка `master`, коммитов нет, все файлы проекта в индексе (`A`). Основная ветка по умолчанию — `main`.
- Версии: Spring Boot 4.1.2, groupId `com.example`, artifactId `banking-service-scientific`, `java.version=17`, Maven 3.10.0 (из wrapper).
- Зависимости: starter-actuator, starter-data-jdbc, starter-validation, starter-webmvc, `org.postgresql:postgresql` (runtime) и тестовые стартеры.
- Среда: на машине установлена **JDK 25.0.1** (не 17); `JAVA_HOME` не задан. Сборка с `java.version=17` идёт на JDK 25 с таргетом 17 — работает. Docker и psql в `PATH` отсутствуют. Установлен PostgreSQL 18 (службы `PostgreSQL`, `postgresql-x64-18`, порт 5432, `C:\Program Files\PostgreSQL\18\bin`), пароль пользователя мне неизвестен.

**Базовая сборка** (`./mvnw.cmd -B test` до изменений): компиляция успешна; тест `contextLoads` **упал** — `Failed to determine a suitable driver class` (нет `spring.datasource.url`). Это ожидаемо: БД в исходном проекте не настроена. Версии не менялись.

## Этап 1. PostgreSQL и схема — выполнен

**Изменено/создано:**
- `pom.xml` — добавлены `spring-boot-starter-flyway` и `flyway-database-postgresql` (runtime). Версии управляются родителем Spring Boot.
- `src/main/resources/application.properties` — datasource из `BANK_DB_URL`, `BANK_DB_USER`, `BANK_DB_PASSWORD` (значения по умолчанию: `jdbc:postgresql://localhost:5432/bank_test`, пользователь `bank`, **пароль пустой**); Flyway включён, `spring.sql.init.mode=never` (единственный механизм схемы — Flyway).
- `src/main/resources/db/migration/V1__create_schema_and_test_accounts.sql` — таблицы `accounts` и `transfers` со всеми ограничениями ТЗ (PK, CHECK `balance_minor >= 0`, UNIQUE `idempotency_key`, FK на счета, CHECK `amount_minor > 0`, CHECK `from_account_id <> to_account_id`), индекс `(created_at DESC, id DESC)` для истории и три тестовых счёта 1001/1002/1003 («Счёт А/Б/В») по 10 000 000 копеек.
- `scripts/reset-test-data.sql` — ручной сброс в одной транзакции: удаляет переводы, возвращает балансы. Приложением не вызывается.
- `.env.example` — переменные БД без пароля.
- `.gitignore` — добавлен `.env`.
- Бизнес-код не писался.

**Проверка.** Пароль к установленному PostgreSQL мне неизвестен (подбирать не стал), поэтому для проверки поднят **временный изолированный кластер** PostgreSQL 18.4 (`initdb` во временном каталоге, порт 55432, БД `bank_test`). Установленная у пользователя БД не затрагивалась. Результаты:
1. Пустая БД + `./mvnw.cmd test`: Flyway применил V1; `BUILD SUCCESS`, `Tests run: 1, Failures: 0` (контекст приложения поднимается).
2. Таблицы `accounts`, `transfers`, `flyway_schema_history`; три счёта, сумма балансов = 30 000 000 копеек.
3. Перезапуск: баланс вручную изменён (9 999 999 / 10 000 001 / 10 000 000), затем приложение запущено повторно — Flyway: `Schema "public" is up to date. No migration necessary`; балансы не изменились, в `flyway_schema_history` одна запись. Повторного пополнения нет.
4. Ограничения проверены прямыми SQL-запросами: перевод себе, сумма 0, несуществующий счёт, дубль ключа, отрицательный баланс — все отклонены БД; корректная вставка прошла.
5. `scripts/reset-test-data.sql` выполнен: переводов 0, все балансы 10 000 000.
6. Временный кластер остановлен, каталог удалён.

**Оставшиеся ограничения.**
- На БД пользователя (порт 5432) миграция не запускалась — нужны пароль и созданная БД. Для запуска: создать БД (например, `bank_test`), задать `BANK_DB_URL`, `BANK_DB_USER`, `BANK_DB_PASSWORD`, выполнить `mvnw.cmd spring-boot:run`.
- Без заданных переменных БД (и работающего PostgreSQL) `contextLoads` теперь падает по подключению — зависимость от тестовой БД; изолированные интеграционные тесты — этап 5.
- Сборка проверена на JDK 25 (таргет 17), а не на самой JDK 17.
- README пока не создан (по плану — этап 14).
- Ничего не закоммичено.

## Этап 2. Чтение счетов и единый формат ошибок — выполнен

**Создано** (пакет `com.example.bankigservicescientific`):
- `account/Account.java` — record `{id, displayName, balanceMinor}`.
- `account/AccountRepository.java` — чтение через `JdbcClient` (`ORDER BY id`, поиск по ID).
- `account/AccountController.java` — `GET /api/accounts`, `GET /api/accounts/{id}`.
- `account/AccountNotFoundException.java`.
- `error/ApiError.java` — тело ошибки `{code, message, timestamp}`.
- `error/GlobalExceptionHandler.java` — `@RestControllerAdvice`: 404 `ACCOUNT_NOT_FOUND` / `NOT_FOUND`, 400 `INVALID_REQUEST` (неверный ID, нечитаемое тело), 405 `METHOD_NOT_ALLOWED`, 503 `SERVICE_UNAVAILABLE` (БД недоступна), 500 `INTERNAL_ERROR`. Stack trace и SQL только пишутся в лог, в ответ не попадают.
- `src/test/.../AccountApiTests.java` — 5 тестов MockMvc (порядок и поля списка, один счёт, 404, 400, 404 несуществующего пути). Тесты не зависят от текущих значений балансов.

**Проверка** (временный кластер PostgreSQL 18.4 на порту 55432, затем удалён):
- `./mvnw.cmd -B test`: `Tests run: 6, Failures: 0, Errors: 0` (5 новых + contextLoads), `BUILD SUCCESS`.
- Живое приложение (`spring-boot:run`, порт 8090) и curl:
  - `GET /api/accounts` → 200, счета 1001, 1002, 1003 по порядку;
  - `GET /api/accounts/1001` → 200;
  - `GET /api/accounts/9999` → 404 `{"code":"ACCOUNT_NOT_FOUND",...}`;
  - `GET /api/accounts/abc` → 400 `INVALID_REQUEST`;
  - `GET /api/nothing` → 404 `NOT_FOUND`; `POST /api/accounts` → 405;
  - в теле ошибки нет слов exception / SQL / трассировки.
  - при остановленной БД `GET /api/accounts` → 503 `SERVICE_UNAVAILABLE`.

**Ограничения.**
- Проверка выполнена на временной БД; на БД пользователя (порт 5432, пароль мне неизвестен) пользователь проверяет сам (`http://localhost:<порт>/api/accounts`).
- Ошибки валидации тела запроса (400 для POST) и 409 появятся с этапом 3.
- Порт 8080 на машине занят службой NI Application Web Server; приложение запускали на другом порту через `SERVER_PORT`.
