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

## Этап 3. Транзакционный перевод — выполнен

**Создано / изменено** (пакет `com.example.bankigservicescientific`):
- `transfer/TransferController.java` — `POST /api/transfers`, обязательный заголовок `Idempotency-Key` (1..128 символов, иначе 400), ответ 201 с `{id, fromAccountId, toAccountId, amountMinor, status, createdAt}`.
- `transfer/TransferRequest.java` — DTO с Bean Validation (`@NotNull`, `amountMinor` от 1 до 1 000 000).
- `transfer/TransferService.java` — публичный метод `@Transactional`: проверка (разные счета, диапазон суммы) → `SELECT ... FOR UPDATE` двух счетов в порядке возрастания ID → проверка существования и достаточности средств → списание, зачисление, вставка записи перевода (ключ, SHA-256 отпечаток `from|to|amount`, статус `COMPLETED`, время UTC). Любое исключение откатывает всё.
- `transfer/TransferRepository.java`, `transfer/Transfer.java`, `transfer/InsufficientFundsException.java`, `transfer/InvalidTransferException.java`.
- `account/AccountRepository.java` — добавлены `lockByIdsOrdered` и `addToBalance`.
- `error/GlobalExceptionHandler.java` — добавлены: 400 (неверное тело, нет заголовка, перевод себе), 409 `INSUFFICIENT_FUNDS`, 409 `IDEMPOTENCY_KEY_CONFLICT` (временно: повторное использование ключа отклоняется, полноценный повтор — этап 4).
- `application.properties` — `spring.jackson.deserialization.accept-float-as-int=false`. Причина: тест выявил, что сумма `1.5` молча усекалась до 1 копейки; теперь даёт 400.
- `src/test/.../TransferApiTests.java` — 11 интеграционных тестов с проверкой фактического состояния БД.

**Проверка** (временный кластер PostgreSQL 18.4, порт 55432, затем удалён):
- `./mvnw.cmd -B test`: `Tests run: 17, Failures: 0, Errors: 0`, `BUILD SUCCESS`. Первый прогон выявил дефект с дробной суммой (1 падение), после исправления все проходят.
- Тесты покрывают: удачный перевод (оба баланса, запись, ключ, отпечаток 64 символа, сумма сохранена); недостаток средств; перевод точно на весь баланс (остаётся 0, не отрицательный); перевод себе; сумма 0, отрицательная и 1 000 001; максимум 1 000 000; неизвестный счёт (отправитель и получатель); отсутствующий/пустой/слишком длинный ключ, ключ из 128 символов; неверное тело (`{}`, не JSON, нет суммы, строка, дробь); откат после частичного выполнения (повтор ключа: списание и зачисление уже выполнены, INSERT падает — балансы возвращаются); переводы в противоположных направлениях. После каждой ошибки проверены число переводов, сумма всех балансов и баланс счёта.
- Живой curl на порту 8090: 201 / 409 / 400 / 400 / 404 / 400 / 400 / 400 для всех перечисленных случаев.
- 60 одновременных переводов 1001↔1002 (30 × 10 и 30 × 7 копеек): все 201, без взаимных блокировок; балансы 9 999 910 / 10 000 090 / 10 000 000 совпали с расчётом, переводов 60, сумма 30 000 000.

**Ограничения.**
- Идемпотентность неполная (по плану): точный повтор ключа пока даёт 409, а не 200 с тем же переводом; одновременные повторы одного ключа — этап 4. `GET /api/transfers...` тоже этап 4.
- `TransferApiTests` перед каждым тестом очищает `transfers` и возвращает балансы трёх счетов к 10 000 000; запускать только на тестовой БД. Отдельная тестовая БД и конкурентные тесты — этап 5.
- Проверка выполнена на временной БД; на БД пользователя (порт 5432) не запускалась.
- Ничего не закоммичено на этапе 3 (состояние коммитов пользователя могло измениться).

## Этап 4. Идемпотентность и чтение переводов — выполнен

**Изменено / создано** (пакет `com.example.bankigservicescientific.transfer`, `error`):
- `TransferService.transfer` (по-прежнему публичный `@Transactional`) теперь возвращает `TransferResult(transfer, created)`. Порядок: валидация → `pg_advisory_xact_lock(hashtextextended(ключ, 0))` (запросы с одним ключом выстраиваются в очередь) → поиск перевода по ключу → при совпадении отпечатка возвращается сохранённый перевод без изменения балансов, при несовпадении — `IdempotencyConflictException` → иначе блокировка счетов, проверки, списание/зачисление, вставка. Ограничение UNIQUE на `idempotency_key` оставлено как последний рубеж (его срабатывание даёт 409 и пишется в лог).
- `TransferService.get` / `latest` — чтение (`@Transactional(readOnly = true)`).
- `TransferRepository` — `findByIdempotencyKey`, `findById`, `findLatest(limit)` (порядок `created_at DESC, id DESC`), `lockIdempotencyKey`.
- `TransferController` — `POST` отвечает 201 (новый) или 200 (повтор); `GET /api/transfers/{id}`; `GET /api/transfers?limit=` (по умолчанию 20, допустимо 1..100, иначе 400; пустое `limit=` трактуется Spring как отсутствующий параметр).
- Новые исключения `IdempotencyConflictException` (409 `IDEMPOTENCY_KEY_CONFLICT`), `TransferNotFoundException` (404 `TRANSFER_NOT_FOUND`) и их обработчики. Неверный UUID в пути → 400.
- Тесты: `IdempotencyAndReadApiTests` (9), `TransferRollbackTests` (1: `@MockitoSpyBean` заставляет INSERT перевода падать уже после списания/зачисления; проверено в БД, что балансы и число переводов не изменились). Из `TransferApiTests` убран прежний тест отката, основанный на повторе ключа (теперь повтор даёт 409 до списания).

**Проверка** (временный кластер PostgreSQL 18.4, порт 55432, затем удалён):
- `./mvnw.cmd -B test`: `Tests run: 26, Failures: 0, Errors: 0`, `BUILD SUCCESS` (строка `ERROR ... Unexpected error` в логе — ожидаемый смоделированный сбой в тесте отката). Тесты покрывают: повтор → 200, тот же перевод (JSON идентичен), одно списание; 5 повторов подряд; тот же ключ с другой суммой / другим получателем / другим отправителем → 409, балансы не меняются; повтор работает, даже если отправитель уже не может платить; неуспешный запрос (недостаток средств) не «сжигает» ключ; `GET` по ID возвращает те же данные; 404 и 400; список: новые первыми, 25 переводов → по умолчанию 20, `limit=3/1/100`; `limit` = 0, −1, 101, abc, 1.5 → 400.
- Живой curl (порт 8090): первый POST 201, точный повтор 200 с тем же `id` и `createdAt`, другое тело → 409.
- Конкурентность: 40 одновременных одинаковых запросов с одним ключом → 1×201 и 39×200, в `transfers` одна запись; 30 одновременных запросов с одним ключом и двумя разными телами → 1×201, 14×200, 15×409, одна запись. Балансы совпали с расчётом (победитель — перевод 101 копейка), сумма 30 000 000.

**Ограничения.**
- Автоматические конкурентные тесты (одновременные переводы, одновременный повтор ключа) — этап 5; здесь конкурентность проверена вручную одним прогоном.
- Сценарий «процесс упал до/после коммита» проверен только на уровне транзакции БД (тест отката), без реального падения процесса.
- Проверка выполнена на временной БД; на БД пользователя (порт 5432) не запускалась.
- Тесты, как и на этапе 3, очищают `transfers` и сбрасывают балансы тестовых счетов: запускать только на тестовой БД.
