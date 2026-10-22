# Полный аудит кода Marketing AI Platform — 2026-10-22

**Репозиторий:** `D:\Marketing-AI`, ветка `master`, HEAD `0d20044` (Phase 11). **Аудитор:** Claude Fable 5.1 (шесть параллельных аудиторов по областям + сводная перепроверка ключевых находок по коду). **Режим:** только чтение; ни один файл проекта не изменён. **Предыдущие полные проверки:** `conformance-review-2026-10-20.md`, аудит 2026-10-17 (§10, десять шагов исправлений), `ml-math-audit.md`.

**Что аудировалось.** Весь код трёх сервисов: `platform-api` (333 Java-файла, ~34 000 строк, 89 контроллеров, 94 сервиса, 56 шедулеров, 21 сущность, 136 миграций), `ml-service` (139 файлов, ~18 900 строк, 48 роутеров / 149 маршрутов, 83 сервиса), `frontend` (54 файла, ~6 800 строк, 25 страниц, 14 блоков лендинга), docker/конфигурация/скрипты, и соответствие кода документам `.ai/` (architecture v1.7, platform-capabilities, roadmap, meta-api-policy v1.2, landing-hosting, creative-publishing). Каждая находка подтверждена чтением кода с указанием `файл:строка`; все P0 и большинство P1 дополнительно перепроверены сводным аудитором. Находки, не подтвердившиеся при перепроверке, исключены.

---

## 1. Итог

**Вердикт: архитектурный каркас крепкий, но к пилоту с реальными деньгами проект не готов — из-за четырёх P0 и примерно тридцати P1, большинство из которых «тихие»: защитный механизм заявлен в документе, выглядит работающим в UI, а в Meta или в БД ничего не делает.**

Что подтверждено и в порядке (детали в разделах 3–8):

- Мультитенантность: приложение ходит в Postgres непривилегированной ролью `mai_app`, ml-service — `mai_ml`; RLS с FORCE и bypass-GUC покрывает 140 из 141 таблиц с `tenant_id`; GUC ставится перед каждым statement через прокси соединения и сбрасывается при возврате в пул; кросс-tenant утечек через аутентифицированные эндпоинты не найдено.
- Единственный писатель `thompson_arms` — ML (грант отозван у `mai_app`); propensity пишется в `decision_log` до ответа; α/β целочисленны; decay ленивый и относительный; reward идемпотентен по `(decision_id, reward_type)`.
- Политика записей в Meta: gate стоит перед 10 из 11 вызовов `MetaApiService`, защитные паузы не блокируются, стадии переходят по данным, override журналируется; ротация токена и OPE-gate из прошлого ревью реально закрыты.
- CAPI: единственный отправитель, только факты из GHL-вебхука, SHA-256, детерминированный `event_id`, ретраи с backoff, fail-closed на неизвестном условии.
- Контракт фронт ↔ бэк по путям и методам: все 60+ вызовов 25 страниц находят свой контроллер.
- SQL-инъекций нет ни в Java, ни в Python; `ffmpeg` без shell; pickle-RCE закрыт HMAC.

### 1.1. Результаты механических проверок (2026-10-22)

| Проверка | Результат |
|---|---|
| Java-тесты (`gradlew cleanTest test --offline`) | **295 passed**, 0 failed, 0 skipped (чисто юнитовые, БД не трогают) |
| ML-тесты (`pytest -q`) | **1097 passed**, 27 warnings, 11 мин 25 с (без БД, Redis и FastAPI — все I/O замоканы) |
| `tsc --noEmit` | чисто |
| `scripts/check-sql.py` (все 136 миграций на чистой БД + 625 встроенных SQL) | **623 ok, 0 failed**, 2 неизвестных типа параметра, 10 динамических и 14 JPQL пропущены |
| `scripts/check-ml-routes.py` | 148 маршрутов, 0 Java-вызовов без маршрута (обратная проверка не выполняется — см. X-16) |
| `scripts/verify-migrations.sh` | **сломан** с V127 (X-7), не запускался |
| Живой запуск стека, живые Meta/GHL | не выполнялись (нет ключей); 9 пунктов для проверки вживую — в §4.5 |

### 1.2. Сводка находок по серьёзности

| Область | P0 | P1 | P2 | P3 | Всего |
|---|---|---|---|---|---|
| S — безопасность и мультитенантность | 0 | 4 | 12 | 8 | 24 |
| M — интеграции Meta/CAPI/GHL и политика записей | 2 | 10 | 13 | 6 | 31 |
| D — данные и фоновые джобы | 0 | 9 | 14 | 4 | 27 |
| ML — ml-service | 1 | 6 | 11 | 8 | 26 |
| F — фронтенд и контракт | 0 | 4 | 15 | 8 | 27 |
| X — документы, инфраструктура, репозиторий | 4 | 9 | 10 | 3 | 26 |
| **Итого (с учётом дублей между областями ≈ 150 уникальных)** | **7** | **42** | **75** | **37** | **161** |

Критерии: P0 — утечка секретов/данных, потеря денег, компрометация tenant'а, блокировка прода, ложная защита; P1 — функциональная ошибка, которая проявится на пилоте; P2 — долг, надёжность, производительность; P3 — косметика.

---

## 2. Главные находки и план исправлений

### 2.1. P0 — до любого дальнейшего шага

| ID | Что | Где | Почему P0 |
|---|---|---|---|
| X-1 | **Живые AWS- и Anthropic-ключи в отслеживаемом файле `amazon bedrock settings.txt`**, в истории с коммита `ce9904a` (2026-10-17) | корень репозитория | Любая копия репозитория или первый push раскрывает ключи. Remote пока нет. Ротировать ключи сегодня, затем `git rm --cached` + `git filter-repo` |
| M-1 | `POST /meta/audiences/sync` создаёт Custom Audience и заливает хэши e-mail **мимо `MetaWriteGate`** и политики стадий; использует CAPI-токен вместо Marketing-токена; создаёт новую аудиторию при каждом вызове; без фильтра `self_accredited` | `MetaAudienceController.java:56-175` | Запись в Marketing API в обход всей защиты аккаунта (политика §4, §8); guard-тест её не видит |
| M-2 / D-1 | «P0-автопауза» при падении сайта и обвале CVR, kill на гейтах, ENFORCE-пауза фрода и resume **не вызывают Meta** — только `UPDATE meta_ad_sets SET status='PAUSED'` в БД; алерт сообщает «ad sets paused» | `SiteHealthScheduler.java:127-137`, `CvrCollapseScheduler.java:117-127`, `GateEvaluationService.java:354-362`, `FraudL2Scheduler.java:111-114`, `SiteHealthResumeService.java:106-107` | Расход в Meta продолжается во время инцидента, БД рассинхронизирована; главная обещанная защита бюджета — фикция |
| ML-1 | Синхронные вызовы Claude/OpenAI/Whisper/ffmpeg/boto3 внутри `async def` на одном uvicorn-воркере; рендер держит `subprocess.run(…, timeout=300)` в event loop | `video.py:365`, `brief.py:370-402`, `compliance_service.py:42`, `content.py:55,73,86`, ещё 6 мест | Любой LLM-вызов или рендер замораживает `/decide/landing` — путь клик → лендинг |
| X-2 | Postgres `5432` и Redis `6379` опубликованы на всех интерфейсах, Redis без пароля, пароли и `JWT_SECRET` захардкожены; prod-профиля нет | `docker-compose.yml:6,23,50` | Этот же compose использовался для живого запуска; на сервере с публичным IP — открытая база |
| X-3 | Бэкапов БД нет вообще (ни `pg_dump`, ни WAL, ни скрипта) | весь репозиторий | Один volume `pgdata` на одном диске (см. также решение по серверу) |
| X-4 / S-2 | Без `ENCRYPTION_KEY` `TokenVault` **молча пишет токены Meta/GHL/CAPI открытым текстом**; переменной нет ни в compose, ни в README, ни в `.env.example` | `TokenVault.java:47-48` | Документы обещают AES в БД; в единственной имеющейся конфигурации шифрование выключено |

### 2.2. P1 — до первой рекламы с бюджетом (сгруппировано)

**Деньги и аккаунт Meta**
- M-3: probe-модерация считает `PAUSED` = APPROVED → объявление, созданное на паузе, может быть «одобрено» до вердикта Meta (проверить вживую; тест закрепляет ошибку).
- M-4: публикация **видео**-креатива упадёт на `/adcreatives` — `thumbnail_url` нигде не записывается.
- M-5: `publish` не идемпотентен (двойной клик = два объявления), 3–4 HTTP-вызова Meta внутри `@Transactional`.
- M-6: linkage health блокирует LAUNCH всему тенанту из-за любого старого **выключенного** ручного объявления; первый прогон шлёт алерт на каждое.
- M-7: ротация по возрасту неприменима к System User token → на 58-й день валидный токен помечается INVALID и автономия падает; при этом gate не читает `meta_token_status`.
- M-8 / D-9: `RestTemplate` без таймаутов везде; коды Meta 190/4/17/32/80004 не разбираются; rate-limit `X-Business-Use-Case-Usage` не читается; при исчерпании бакета P1/P2 молча отбрасываются.
- M-9: `AutopilotService.executeAction` только пишет строку `EXECUTED` — `PAUSE_BURNOUT` ничего не паузит.
- M-10 / D-3: L1 budget guard никогда не сработает — `current_spend` никто не кормит; при срабатывании только алерт.
- M-11: все ad set платформы `optimization_goal=REACH` без `promoted_object` — CAPI-оптимизация платформенными ad set не используется.
- M-12: guard-тест `MetaWriteGuardTest` не ловит `upsertCustomAudience`, обходится переименованием поля, не видит записей мимо `MetaApiService`.

**Данные и ядро обучения**
- D-2: `QualityScoreScheduler` — окно `[now-16min, now-30min)` пустое → `quality_score` **никогда** не считается; каскадом мертвы ProxyCalibration, `creative_quality`, clickbait-детект, компонент QS в отчёте.
- D-7: `WeeklyReportScheduler` — первый тенант цикла считается под RLS-bypass → отчёт с цифрами **всех** тенантов уходит в Telegram/e-mail.
- D-5: 14 FK к `sessions` без `ON DELETE` → GDPR-erasure падает 500 уже сейчас, retention упадёт на 13-м месяце после того, как `events` уже удалены.
- D-4: `NicheBenchmarkScheduler` — декартово произведение трёх таблиц на тенанта ежедневно в 03:00.
- D-6: `creative_quality` без UNIQUE → `ON CONFLICT DO NOTHING` никогда не срабатывает (латентно, проявится после фикса D-2).
- D-8: Redis — жёсткая зависимость ingestion без degradation; `markSeen` до записи в БД.
- ML-3: одно решение может дать до 7 α-инкрементов вопреки задокументированной модели «одна Bernoulli на решение».
- ML-5: `/decide/reward` не привязан к tenant/session; `/public/reward` без rate-limit → накачивание α выбранных плеч из браузера.
- ML-7: вариант, активированный после первого решения, **никогда** не получает плеча (`ensure_arms` вызывается только при пустом наборе) → не показывается.
- ML-6: `/glm/fit` и `/propensity/fit` никто не вызывает → GLM-приоры и обученная propensity-модель не работают.
- ML-2: `NameError` в `analyze.py:167` → таксономическое выравнивание атомов никогда не выполняется, ошибка глотается.
- ML-4: SSRF-guard применён к 2 из 9 точек скачивания URL в ml-service.

**Безопасность**
- S-1: `creative_download_mode = PREVIEW_ONLY` никогда не срабатывает (запрос под пустым tenant-контекстом → RLS даёт 0 строк).
- S-3: Meta-токен и `client_secret` в query-строке → при ошибке попадают в логи и в тело 502.
- S-4: глобальный `GHL_WEBHOOK_SECRET` — fallback для любого тенанта → один клиент может слать «won»-события в чужой tenant.
- X-6 / S-9: reCAPTCHA fail-open; нет rate-limit на `/auth/*`, `/events`, `/public/form/submit`, `/public/reward`; регистрация открыта.

**Фронтенд и инфраструктура**
- F-1: `frontend/Dockerfile` не соберётся — каталога `public` нет (ломает и раннер, режим C).
- F-2: Next 15.0.3 — CVE-2025-29927 (обход middleware одним заголовком).
- F-3: гонка refresh-токенов — пользователь разлогинивается на любой «тяжёлой» странице после истечения access-токена.
- F-4: страница `/briefs/[id]` читает несуществующие поля — статус всегда APPROVED, кнопка Approve не появляется.
- X-7: `verify-migrations.sh` сломан с V127. X-8: единственный профиль `local` с DEBUG security-логами. X-9: нет CI. X-10: нет `.dockerignore`, `COPY . .` затянет `.env`. X-11: compose без restart/healthcheck/лимитов. X-12: `mlflow` бесполезен и пишет в прод-БД. X-13: `.env.example` и README неполны.

### 2.3. Рекомендуемый порядок

**Шаг 0 (сегодня):** ротировать AWS- и Anthropic-ключи; убрать `amazon bedrock settings.txt` из индекса и истории; `.gitignore`; gitleaks в pre-commit (X-1).

**Шаг 1 (неделя, «деньги и аккаунт»):** M-1, M-2/D-1, M-10/D-3, M-9 — все паузы и записи через `MetaApiService` + `MetaWriteGate`; усилить `MetaWriteGuardTest` (M-12); таймауты и разбор кодов Meta (M-8/D-9); fail-fast без `ENCRYPTION_KEY` (X-4); токен в заголовке (S-3); убрать глобальный GHL-секрет (S-4).

**Шаг 2 (неделя, «ядро обучения»):** ML-1 (async-клиенты, рендер в очередь, несколько воркеров), ML-7, ML-3 (решить семантику наград), ML-5 (привязка reward к session + rate-limit), ML-2, ML-6 (планировщик для `/glm/fit`, `/propensity/fit`), D-2 + D-6, D-7, D-8.

**Шаг 3 (до первого видео-объявления):** M-3 (проверить вживую семантику `effective_status`), M-4 (thumbnail), M-5 (идемпотентность), M-6, M-7, M-11; F-1, F-2, F-3, F-4, F-10; S-1.

**Шаг 4 (инфраструктура, параллельно):** X-2, X-3, X-7…X-13; D-5 (FK + DROP PARTITION), D-4, D-9/D-12 (Hikari, таймауты), D-18 (ShedLock), D-20 (ретеншен).

**Шаг 5 (документы):** синхронизировать architecture §4/§5/§9/§10 и заголовок версии, roadmap 11-4a/9-19/11-5a/11-3b/11-3c, Bug List, capabilities (стабы из §8.4 пометить явно).

### 2.4. Что проверить вживую с реальным аккаунтом Meta (ни один из пунктов нельзя закрыть чтением кода)

1. `effective_status` объявления с `status=PAUSED` во время ревью (M-3).
2. `/adcreatives` с `video_data` без `image_hash` (M-4).
3. Поддержка Graph API v20.0 и судьба `standard_enhancements` OPT_OUT (M-22).
4. `optimization_goal=REACH` при objective `OUTCOME_LEADS` (M-11).
5. `GET /act_X/ads` без фильтра статуса — включаются ли ARCHIVED (M-6, M-28).
6. `fb_exchange_token` для System User token (M-7).
7. Имена событий GHL (`CAPIForwardingService:55`).
8. EMQ при `action_source=system_generated` без fbp/fbc/IP (M-13).
9. Что `e.getMessage()` у `HttpClientErrorException` содержит URL с токеном (S-3).

---
## 3. Безопасность и мультитенантность (platform-api)

**Охват.** Прочитаны `security/` (6/6), `config/` (6/6), 19 контроллеров с публичными или чувствительными входами, 17 сервисов (auth, vault, домены, DNS, ingestion, storage, alert, ML-клиент, Meta token exchange), сущности/репозитории пользователей, tenant'ов, доменов и runner-ключей, миграции V1, V3, V5, V9, V10, V25, V86, V112, V116, V119, V127, V128, V133, V135, V136 полностью; по всем 136 миграциям прогнан скрипт «таблицы с `tenant_id` × ENABLE/FORCE/POLICY». Grep по `src/main/java`: `runAsSystem/callAsSystem` (16 вызовов), `vault.seal/open` (28), `@Async/Executor/Thread` (нет), `@PreAuthorize` (нет), `@ControllerAdvice` (нет).

### 3.1. Инварианты, подтверждённые кодом

| Инвариант | Где | Доказательство |
|---|---|---|
| Приложение ходит в БД не владельцем, Flyway — владельцем | `application-local.yml:214-227` | `username: ${DB_APP_USER:mai_app}` … `flyway.user: ${DB_USER:mai}` |
| GUC ставится перед каждым statement, сессионно, сбрасывается при `close()` | `security/TenantScopedConnection.java:367-450` | `APPLY_SQL = "SELECT set_config('app.current_tenant', ?, false), set_config('app.rls_bypass', ?, false)"`; `ensureScope()` на `createStatement/prepareStatement/prepareCall/nativeSQL`; `resetScopeQuietly()` на `close` |
| Без JWT tenant = `''` → RLS возвращает 0 строк (fail-closed); bypass только явный | `TenantScopedConnection.java:411-416`, `TenantContext.java:298-336` | `tenantValue = isValidUuid ? tenant : ""`; `bypass = isSystem() ? "on" : "off"`; `callAsSystem` восстанавливает scope в `finally` |
| HTTP-запрос никогда не получает bypass по умолчанию; системный scope — 16 явных вызовов | grep | auth (4), события (1), вебхуки (3), public slug/host/runner-key (6), startup reseal (1), TenantDomainService (4) |
| Все `@Scheduled` в системном scope через один обёртчик; `@Async` в коде нет | `config/SchedulingConfig.java:46-48` | `return () -> TenantContext.runAsSystem(task);` |
| JWT: HS256, секрет ≥ 32 байт обязателен, дефолта нет | `security/JwtUtil.java:79-86`, `application.yml:171` | `throw new IllegalStateException("JWT_SECRET is missing or shorter than 32 bytes…")` |
| Refresh-токены как SHA-256, ротация при refresh, logout ревокирует | `service/AuthService.java:140-189` | `WHERE rt.token_hash = ? AND NOT rt.revoked AND rt.expires_at > NOW()` |
| BCrypt; CSRF отключён при stateless Bearer | `config/SecurityConfig.java:36-37,70-72` | `csrf.disable()`, `STATELESS`, `new BCryptPasswordEncoder()` |
| Токены Meta/CAPI/GHL/каналов не возвращаются API | `TenantController.java:41-56`, `dto/pixel/PixelResponse.java:25`, `GhlSubaccountController.java:38-43` | `(meta_access_token_enc IS NOT NULL) AS has_token` |
| Запись токенов через `TokenVault.seal`, чтение `open/openOrNull` | `TenantService.java:143`, `MetaPixelService.java:79,100`, `GhlSubaccountController.java:68`, `ChannelController.java:91` + 24 места чтения | `tenant.setMetaAccessTokenEnc(vault.seal(req.getAccessToken()))` |
| Вебхук GHL: constant-time сравнение, пустой секрет = отказ | `GhlWebhookController.java:159-162` | `MessageDigest.isEqual(…)` |
| Runner-ключ хранится как SHA-256, показывается один раз | `TenantDomainService.java:178-193`, `entity/RunnerKey.java:218-220` | `rk.setKeyHash(DomainVerifyMath.sha256Hex(key))` |
| `/public/decide`: ключ должен владеть tenant'ом slug'а; лимит 600/мин | `PublicLandingController.java:89-110,130-138` | `if (!keyTenant.equals(variant.getTenantId())) return 403` |
| `/events`: tenant/variant/creative проверяются серверно, `revenue` обнуляется, server-only типы отклоняются, карантин | `EventIngestionService.java:114-152` | `missing_tenant`, `missing_event_id`, `server_only_event_type`, `variant_tenant_mismatch`, `unknown_tenant`; `e.setRevenue(null)` |
| `/public/form/submit`: tenant из варианта, не из тела | `PublicLandingController.java:152-157` | `callAsSystem(() -> formSubmissionService.variantOwner(variantId))` |
| `/files/**`: path traversal невозможен | `FileController.java:36-43,87-89` | `@PathVariable UUID`, `replaceAll("[^a-zA-Z0-9._-]", "_")` |
| DNS только по валидированному FQDN | `DomainVerifyMath.java:335-359`, `DnsLookup.java:40-61` | regex `FQDN`, отказ `localhost`/IPv4/`[`; JNDI DNS с таймаутом 3 с |
| ML-ключ подставляется только для URL ML-сервиса | `RestClientConfig.java:107-116` | `request.getURI().toString().startsWith(base)` |
| Роли: любой write не для VIEWER; `/tenants/**`, `/agency/**` — ADMIN | `SecurityConfig.java:42-49` | `requestMatchers(POST, "/**").hasAnyRole(WRITE_ROLES)` |
| RLS: таблицы с `tenant_id` ≤ V116 покрыты динамически; 13 из 14 таблиц после V116 имеют ENABLE+FORCE+policy с bypass | `V116:19-46`; скрипт по V117–V136 | V121, V123, V124×2, V125, V131, V132, V133×2, V134, V135×2, V136 — OK |
| `ensure_events_partition` (SECURITY DEFINER) валидирует имя, выдан только `mai_app` | `V128:24-44` | `IF p_name !~ '^events_[0-9]{4}_[0-9]{2}$' THEN RAISE EXCEPTION` |
| Стектрейсы наружу не уходят | нет `@ControllerAdvice`, нет `server.error.include-*` | Spring Boot 3 default `include-message=never`, `include-stacktrace=never` |

### 3.2. Находки

| ID | Серьёзность | Где | Что не так | Доказательство | Как исправить |
|---|---|---|---|---|---|
| S-1 | **P1** | `controller/FileController.java:50,65-73` | `creative_download_mode = PREVIEW_ONLY` (Phase 11, правило 2) **никогда не срабатывает**: `/files/**` permitAll, JWT нет → `TenantContext` пуст → `app.current_tenant=''` → RLS на `tenants` даёт 0 строк → `previewOnly()` = false, финальный рендер отдаётся всегда. То же `isFinalRender()` (RLS на `creatives`) | `SELECT settings->>'creative_download_mode' FROM tenants WHERE id = ?` без `callAsSystem`; `catch (Exception e) { return false; }` | Обернуть оба запроса в `TenantContext.callAsSystem(...)`; тест с реальной RLS. При `STORAGE_TYPE=r2` контроллера нет вообще — файлы публичны по прямой ссылке |
| S-2 | **P1** | `service/TokenVault.java:114-132`, `EncryptionService.java:30-45`, `application.yml:202-203` | Без `ENCRYPTION_KEY` vault **молча пишет токены открытым текстом** (только warn). Нет prod-профиля, нет fail-closed. В `docker-compose.yml` `ENCRYPTION_KEY` не задан | `if (!encryption.isConfigured()) { return plaintext.getBytes(UTF_8); }`; `key: ${ENCRYPTION_KEY:}` | В не-`local` профиле падать на старте без ключа (как `JwtUtil.validateSecret`) |
| S-3 | **P1** | `scheduler/TokenRotationScheduler.java:112-128`, `service/MetaApiService.java:125,222,235,250,261,388,432,453` | Meta access-token (в ротации — ещё и `client_secret`) передаются как query-параметр; при ошибке логируется `e.getMessage()`. В Spring 6.1+ сообщение `RestClientResponseException` содержит полный URL → **секреты в логах** и в `ResponseStatusException(BAD_GATEWAY, "Meta API error: " + e.getMessage())` | `restTemplate.getForObject(MetaTokenExchange.exchangeUrl(appId, appSecret, current), …)`; `.queryParam("access_token", accessToken)` | Токен в `Authorization: Bearer`, обмен токена — POST-телом; логировать только статус/тело ответа; маскировать URL |
| S-4 | **P1** | `GhlWebhookController.java:89,110-115,147-156`, `application.yml:175`, `docker-compose.yml:52` | Глобальный `GHL_WEBHOOK_SECRET` — fallback для **любого** tenant'а без sub-аккаунта. Один общий секрет на всех клиентов → клиент A, зная tenantId клиента B (виден в `GET /public/domain/{host}` и в трекере), шлёт `OpportunityStatusUpdate`/won → Purchase CAPI, конверсии, награды bandit'а для B. Дефолт в compose `local-ghl-secret` | `return appProperties.getGhlWebhookSecret();` после двух per-tenant попыток | Убрать глобальный fallback (fail-closed без своего секрета); UI для per-tenant секрета |
| S-5 | P2 | `EventIngestionService.java:219-256`, `V5__events.sql:1-3` | `sessions.id` — глобальный VARCHAR PK без tenant'а; `/events` в `callAsSystem` делает `INSERT … ON CONFLICT (id) DO UPDATE SET converted/…`. Любой самозарегистрированный tenant, зная session_id чужого визитёра, перезапишет чужую сессию как `converted`. Также события с чужим `tenant_id` + публичным `variant_id` принимаются → подделка `FORM_SUBMIT` → `rewardService.onEventIngested` | `ON CONFLICT (id) DO UPDATE SET … converted = …`; `CONVERSION = Set.of("FORM_SUBMIT",…,"WEBINAR_REG")` | `… DO UPDATE … WHERE sessions.tenant_id = EXCLUDED.tenant_id` или PK `(tenant_id,id)`; запись под `runAs(tenantId)`; rate-limit; `FORM_SUBMIT`-награда только из `/public/form/submit` |
| S-6 | P2 | `PublicLandingController.java:59`, `VariantService.java:236-242`, `LandingVariantRepository.java:18-19`, `V3:15` | Слаг уникален только в tenant'е (`UNIQUE(tenant_id, slug)`), но `/public/l/{slug}` и `/public/decide` без `host` ищут глобально `findBySlugAndStatus` → при двух ACTIVE с одним slug у разных tenant'ов — `IncorrectResultSizeDataAccessException` → 500 (любой tenant «роняет» чужой платформенный лендинг) | `/** slug should be globally unique in practice. */` | Partial unique index на `slug WHERE status='ACTIVE'` либо обязательный `host`/tenant в публичном маршруте |
| S-7 | P2 | `TenantDomainService.java:125-126`, `V135:12` | Домен-сквоттинг: `create()` отказывает, если хост есть у кого угодно **в любом статусе** (PENDING без верификации) — владельца можно заблокировать навсегда | `taken = callAsSystem(() -> domainRepo.findByHost(h).isPresent()); if (taken) throw CONFLICT` | Уникальность только среди VERIFIED; PENDING с TTL; успешная TXT-верификация «отбирает» домен |
| S-8 | P2 | `TenantDomainService.java:211-226`, `DomainController.java` | Runner-ключ работает после `disable` домена (проверяется только `revoked_at IS NULL`); эндпоинта ревокации нет; отозвать можно лишь `DELETE /domains/{id}` | `keyRepo.findByKeyHashAndRevokedAtIsNull(hash)` без проверки `tenant_domains.status` | Проверять статус домена; добавить revoke/список ключей |
| S-9 | P2 | `AuthController.java`, `RecaptchaFilter.java:165-183`, `PublicLandingController.java:144-187`, `EventController.java` | Нет rate-limiting/lockout на `/auth/login`, `/auth/register`, `/auth/refresh`, `/public/form/submit`, `/public/reward`, `/events` (лимит только на `/public/decide`). `RecaptchaFilter` fail-open (без токена/при ошибке пропускает; URL `projects/default` неверный). Регистрация открыта без подтверждения e-mail → любой получает валидный tenantId (предпосылка S-5/S-6/S-7) | `if (token == null \|\| token.isBlank()) { chain.doFilter…; return; }` | Bucket4j/Redis-лимиты; reCAPTCHA обязателен на register/form; инвайт-регистрация на пилоте |
| S-10 | P2 | `AuthService.java:139-167`, `JwtFilter.java` | `refresh()` не проверяет `user.status` (деактивированный продлевает сессию бесконечно); access-JWT 24 ч не ревокируется; logout гасит только refresh | `userRepo.findById(userId).orElseThrow(...)` без проверки `status` | Проверять статус в refresh; короткий access (15–60 мин); logout ревокирует все refresh пользователя |
| S-11 | P2 | `SecurityConfig.java:44`, `ErasureController.java:21-28`, `GhlSubaccountController:46`, `ChannelController:80`, `MetaPixelController:40`, `DomainController:312` | Роли только по URL-префиксам. Матчер `"/erasure/**"` не совпадает с реальным `POST /privacy/erasure` → erasure доступен MARKETER/EDITOR. Ключи GHL, токены каналов/CAPI, runner-ключи, budget guards, publish в Meta — для любой write-роли. Пользователей кроме первого ADMIN создать нечем | `.requestMatchers(POST, "/tenants/**", "/agency/**", "/erasure/**").hasRole("ADMIN")` vs `@RequestMapping("/privacy") … @PostMapping("/erasure")` | Исправить матчер на `/privacy/**`; секреты/ключи/erasure — под ADMIN (`@PreAuthorize`); invite/roles |
| S-12 | P2 | `V119:9-17`; таблицы без `tenant_id`: `agency_accounts`, `agency_kpi_snapshots` (V112), `campaign_cpm_history` (V19) | `atom_registry_tenants` имеет `tenant_id`, но **без RLS** (единственная из 141). Раскрывает чужие tenantId и используемые атомы; низкий ущерб | `CREATE TABLE atom_registry_tenants (…tenant_id UUID NOT NULL …)` без `ENABLE ROW LEVEL SECURITY` | Добавить ENABLE+FORCE+policy либо задокументировать как platform-wide |
| S-13 | P2 | `GhlSubaccountController.java:58-68`, `TokenReencryptRunner.java:196-201` | `ghl_subaccounts.webhook_secret` и `tenants.settings.ghl_webhook_secret` — открытым текстом; в `TARGETS` их нет | `str(body.get("webhook_secret"))` | Хранить SHA-256 секрета или `*_enc` через vault |
| S-14 | P2 | `SecurityConfig.java:39`, `FileController.java:27-31` | Все локальные файлы креативов публичны без аутентификации; защита только непредсказуемостью UUID+имя. Для R2 — публичный bucket | `.requestMatchers(…, "/files/**", "/public/**").permitAll()` | Подписанные URL с TTL либо выдача по JWT; заодно чинит S-1 |
| S-15 | P2 | `src/test/java/com/marketingai/security/RlsIntegrationTest.java:1-30` | Интеграционный тест RLS — заглушка (`assertTrue`); реальная изоляция против Postgres с `mai_app` не проверяется | `TODO: wire full Spring integration context …` | Testcontainers + Flyway + два tenant'а, сценарии `/files`, `/events`, `/public/*` |
| S-16 | P2 | `application.yml:174`, `application-local.yml:214-215,226-227`, `docker-compose.yml:50` | Дефолты: `INTERNAL_API_KEY=local-internal-key`, пароли БД `mai_app_local`/`mai_local`, профиль по умолчанию `local` с `org.springframework.security: DEBUG`. Без переменных в проде тихо поедет с известными секретами (падает только JWT_SECRET) | `internal-api-key: ${INTERNAL_API_KEY:local-internal-key}` | Стартовый guard «не local → все секреты обязательны» |
| S-17 | P3 | `TenantService.java:188`, `AlertService.java:61-67`, `SiteHealthScheduler.java:57-72`, `FormHealthScheduler.java:96` | `slack_webhook_url` без валидации — API-хост POST'ит на произвольный URL (blind SSRF). `settings.base_url` → HEAD-запросы шедулеров на произвольный хост | `restTemplate.postForObject(webhookUrl, …)` | Allow-list `https://hooks.slack.com/`; base_url только из VERIFIED доменов; запрет приватных диапазонов |
| S-18 | P3 | `PublicLandingController.java:66-77`, `EventController.java:46-56` | `GET /public/domain/{host}` отдаёт `tenantId` любому; `resolveIp` доверяет `CF-Connecting-IP`/`X-Forwarded-For` от клиента (подмена IP для fraud-скоринга вне Cloudflare) | `out.put("tenantId", d.getTenantId())` | tenantId из ответа убрать; IP через `ForwardedHeaderFilter` с доверенными прокси |
| S-19 | P3 | `AgencyController.java:98,133`, `FileController.java:52` | `e.getMessage()` отдаётся клиенту — при недоступном ML утечёт `http://ml-service:8000/...`. `X-Error` — нестандартный канал | `"error", e.getMessage()` | Единый `@ControllerAdvice`/ProblemDetail без внутренних URL |
| S-20 | P3 | `application.yml:139-142`, `CreativeService.java:69-71` | Multipart 500 MB и `file.getBytes()` в heap; тип файла не проверяется. DoS памятью аутентифицированным пользователем | `max-file-size: 500MB` | Стримить, allow-list MIME, лимит ниже |
| S-21 | P3 | `TenantScopedConnection.java:396-402` | `unwrap(Connection.class)` возвращает сырой делегат (минуя `ensureScope`). Вызовов в коде приложения нет; Hibernate может вызывать | `else if ("unwrap".equals(name) …) return delegate;` | Возвращать прокси для `Connection.class` |
| S-22 | P3 | `AuthService.java:95-99,122`, `entity/User.java:215-218` | Комментарий «email unique per-tenant», но `existsByEmail`/`findByEmail` глобальные → при втором tenant'е с тем же e-mail `findByEmail` вернёт 2 строки → 500 на логине | `if (userRepo.existsByEmail(req.getEmail())) throw CONFLICT` | Логин по (email, tenantSlug) либо глобальная уникальность в схеме |
| S-23 | P3 | `PublicLandingController.java:166-187` | `/public/reward` принимает любой `decision_id` без проверки принадлежности → можно досыпать `FORM_SUBMIT`-награды по утёкшему decision_id | `mlClient.post("/decide/reward", Map.of("decision_id", …))` | Привязать к session_id/варианту |
| S-24 | P3 | `AgencyController.java:43-68` | Агентские эндпоинты читают `tenants WHERE agency_id = ?` под tenant-RLS, `agency_id` нигде не проставляется → фича мертва, не опасна | без `callAsSystem` | Убрать или явный системный scope с проверкой членства |

### 3.3. Статус замечаний ревью 2026-10-20

| Замечание | Статус | Где |
|---|---|---|
| №13 «Токены не шифруются» | **Закрыто частично.** Все `*_enc` колонки пишутся через `TokenVault.seal`, читаются через `open/openOrNull` (24 места); `TokenReencryptRunner` досаливает легаси-строки на старте. Но без `ENCRYPTION_KEY` — молча plaintext (S-2); `webhook_secret` GHL открытым текстом (S-13); токены в URL/логах (S-3) | `TokenVault.java:125-132`, `TokenReencryptRunner.java:196-236` |
| №4 «ml-service подключается владельцем» | **Закрыто на стороне БД.** V127: `mai_ml LOGIN NOBYPASSRLS`, не владелец, REVOKE на `events`/`ghl_webhook_log`/`flyway_schema_history`; V131–V136 выдают явные гранты | `V127__ml_role.sql:14-31` |
| №1 «`events.event_id` nullable» | **Закрыто функционально, не на уровне схемы.** Колонка nullable (документировано в V128), ингест отбрасывает события без `event_id` в `events_rejected`; `EventIdRequiredTest` | `V128:5-9`, `EventIngestionService.java:118` |
| №12 «ротация токена — заглушка» | **Закрыто.** Реальный обмен `fb_exchange_token`, OBSERVER при INVALID. Побочный эффект — S-3 | `TokenRotationScheduler.java:97-135` |

### 3.4. Проверить вживую

1. S-3: что `e.getMessage()` у `HttpClientErrorException` содержит полный URL с `access_token` — один запрос к Graph API с невалидным токеном и просмотр лога.
2. S-1 на проде с R2: при `STORAGE_TYPE=r2` `FileController` не создаётся → PREVIEW_ONLY не работает ни в одном режиме; проверить приватность бакета.
3. `agency_kpi_snapshots`, `campaign_cpm_history` (без `tenant_id`): если там per-tenant показатели — кросс-tenant чтение.
4. Сообщения ошибок во фронте: Spring Boot по умолчанию не кладёт `message` в тело ошибки, а `frontend/src/lib/api.ts:22-33` читает `body.error` — причины вроде «Tenant slug already taken» до UI не доходят.
5. `ErasureController.java:47-49` читает `sessions.id` как `UUID.class`, хотя колонка `VARCHAR(100)` — если трекер генерирует не-UUID строки, erasure падает.

### 3.5. Итог по области

Фундамент мультитенантности сделан правильно и сильнее, чем на момент ревью 2026-10-20: приложение ходит в Postgres ролью `mai_app`, RLS с FORCE покрывает 140 из 141 таблиц с `tenant_id`, GUC ставится перед каждым statement через прокси соединения и сбрасывается при возврате в пул, шедулеры получают системный scope через один обёртчик, bypass-точки в HTTP-слое перечислимы. Кросс-tenant утечки данных через аутентифицированные эндпоинты не найдено. Четыре P1: неработающий PREVIEW_ONLY на `/files` из-за RLS без scope, fail-open vault без ключа, секреты Meta в URL и логах, общий глобальный GHL-секрет, позволяющий одному клиенту слать «won»-события в чужой tenant. Публичные входы без rate-limiting и с открытой регистрацией дают недорогие сценарии порчи чужих данных (upsert чужой сессии, коллизия slug, сквоттинг доменов). Ролевая модель существует только по URL-префиксам, один матчер не совпадает с реальным путём. Главный долг — отсутствие настоящего интеграционного RLS-теста, который поймал бы S-1.

---

## 4. Внешние интеграции и политика записей в Meta (platform-api)

**Охват.** Прочитаны полностью `MetaApiService`, `MetaApiClient`, `MetaWriteAction/Policy/Gate/StageService/Controller`, `MetaApiAuditService`, `MetaWriteGuardTest`, `CreativePublishService/Math/Controller`, `MetaAdMapController`, `LinkageHealthService`, `AdReconcileMath`, `MetaAdReconcileScheduler`, `ModerationProbeScheduler/Math`, `BlockedCreativeRecoveryScheduler`, `FileController`, `CAPIForwardingService`, `CapiValueModeService/Math`, `CAPIEmqCheckService`, `CapiRetryScheduler`, `ValueFormulaService`, `GhlWebhookController/Service`, `GhlApiClient`, `GhlContactLinker`, `GhlSyncScheduler`, `GhlSubaccountController`, `TokenRotationScheduler`, `MetaTokenExchange`, `TokenVault`, `BudgetGuardService`, `CircuitBreakerScheduler/Service`, `AutonomyService/Gate/Controller`, `AutopilotService/Controller/Scheduler`, `OpeService/Scheduler`, `GoogleAdsApiService`, `TikTokApiService`, `MetaAudienceController`, `MetaCampaignService`, `TwoContourService`, `SiteHealthScheduler`, `CvrCollapseScheduler`, `SiteHealthResumeService`, `RecoveryDiagnosisService`, `MetaSyncScheduler`, `PublicLandingController.decide`, ML `ad_map_service.py` + `decide.py`, frontend `middleware.ts`. Grep: `graph.facebook.com` только в `MetaApiService`, `CAPIForwardingService`, `MetaTokenExchange`, `CompetitiveIntelligenceService` (чтение Ad Library). Обходных вызовов Marketing API мимо `MetaApiService` нет; есть вызов `MetaApiService` мимо `MetaWriteGate` (M-1).

### 4.1. Инварианты, подтверждённые кодом

| Инвариант | Где | Доказательство |
|---|---|---|
| 10 из 11 call-site записи `MetaApiService` предварены `metaWriteGate.assertAllowed/decide` | `MetaCampaignService.java:50,101,140,175`; `TwoContourService.java:119,297,376,502`; `CircuitBreakerService.java:268`; `CreativePublishService.java:148,223,241` | исключение — `MetaAudienceController.java:135` (M-1) |
| Защитная пауза никогда не блокируется | `MetaWritePolicy.java:91-93` | `if (a.protective()) return ALLOW` |
| CREATE_AD_PAUSED разрешён с GUARDED_WRITES, запрещён в READ_ONLY | `MetaWritePolicy.java:106-117`; `MetaWritePolicyTest.java:54-56` | в GUARDED запрещены только CREATE_CAMPAIGN/AUDIENCE_UPDATE и LAUNCH без probe |
| Объявление, кампания и ad set создаются только `status=PAUSED` | `MetaApiService.java:53,86,366` | `.put("status", "PAUSED")` захардкожен |
| Advantage+ standard enhancements OPT_OUT; `url_tags` с `mai_c={{ad.id}}` | `MetaApiService.java:355-357`; `CreativePublishMath.java:12-14` | `standard_enhancements.enroll_status=OPT_OUT` |
| Каждое решение gate пишется в `meta_write_decisions`; RECOMMEND → `autopilot_actions` PENDING + 409; DENY → 403 | `MetaWriteGate.java:44-54, 82-97, 169-179` | INSERT независимо от вердикта |
| Override: причина ≥10 символов, TTL ≤240 мин, одноразовый, не обходит learning-lock для бюджета | `MetaWriteController.java:54-65`; `MetaWriteGate.java:92-93`; `MetaWritePolicy.java:97-102` | `used_at = now()` при первом ALLOW |
| Переходы стадий — по данным; принудительно только с причиной, в журнал | `MetaWriteStageService.java:76-94, 118-166` | CAPI ≥50 SENT и первое ≥14 дней; EMQ; `meta_automated_rules_confirmed`; FULL: OPE PASS ≤7 дн, автономия ≥ SEMI, 0 DENY за 7 дн |
| OPE-gate при апгрейде автономии; еженедельный FAIL понижает до ADVISOR | `AutonomyController.java:543-551`; `OpeScheduler.java:318-329`; `AutonomyGate.java:321-342` | `AutonomyGate.evaluate` перед `setAutonomyLevel` |
| Ротация токена — реальный `fb_exchange_token`; INVALID → OBSERVER с сохранением уровня; восстановление при re-auth | `TokenRotationScheduler.java:110-134, 80-95`; `TenantService.java:53-60` | `vault.seal(fresh)` |
| Единственный отправитель CAPI — `CAPIForwardingService`; события только из фактов GHL-вебхука | `GhlWebhookService.java:198-227` | других вызовов `forwardByStage/forward` нет |
| Хэширование PII SHA-256, email lower+trim, токен в теле POST, детерминированный `event_id`, fail-closed на неизвестном `send_condition` | `CAPIForwardingService.java:174-178, 146, 358-361, 347-355` | — |
| Ретраи CAPI: backoff 5 мин·2^n ≤6 ч, ≤5 попыток, ≤7 дней; `event_time` исходный | `CAPIForwardingService.java:365-409`; `CapiRetryScheduler.java:354` | — |
| GHL вебхук: секрет (per-location → per-tenant → глобальный), constant-time; opportunities только из MAIN | `GhlWebhookController.java:44-59, 93-96` | `MessageDigest.isEqual` |
| Сверка аккаунта read-only, работает при READ_ONLY; drift → DRIFT + алерт один раз | `MetaAdReconcileScheduler.java:49, 108-122` | `listAds` без записи |
| Клик `mai_c` → ML `creative_id` через `meta_ad_map` в обоих режимах (edge и runner) | `middleware.ts:21-33, 145-167`; `PublicLandingController.java:112-117`; `decide.py:148-156`; `ad_map_service.py:22-25` | `context.meta_ad_id` → `resolve_creative_id` |

### 4.2. Сквозной сценарий Phase 11

| Шаг | Где | Статус |
|---|---|---|
| 1. Compliance PASS креатива + текста объявления | `CreativePublishService.java:100-101, 136-140` | работает |
| 2. Readiness: стадия ≥ GUARDED, токен, ad account, Page ID, файл, ACTIVE-slug, host | `CreativePublishService.java:87-109` | работает |
| 3. Gate `CREATE_AD_PAUSED` | `:148` | работает |
| 4. Upload видео/картинки | `MetaApiService.java:274-328` | **рвётся для видео**: `thumbnail_url` нигде не записывается → `video_data` без `image_url/image_hash` (M-4) |
| 5. `/adcreatives` → `/ads` PAUSED | `:161-165` | работает для изображений |
| 6. `meta_ad_map` MANAGED + `publish_status=PENDING_REVIEW` + `moderation_results` PENDING | `:167-196` | работает; нет идемпотентности, HTTP внутри `@Transactional` (M-5) |
| 7. Probe каждые 30 мин по `effective_status`/`ad_review_feedback` | `ModerationProbeScheduler.java:236-279` | **рвётся по смыслу**: объявление создано `PAUSED`, а `ModerationProbeMath` считает `PAUSED` = APPROVED (M-3) |
| 8. Activate (RESUME через gate) | `CreativePublishService.java:212-232` | работает; ad set платформы всегда PAUSED → фактически `ADSET_PAUSED` (M-20) |
| 9. Клик `/l/{slug}?mai_c=<ad.id>` → middleware → decide | `middleware.ts:22-27,145-167`; `PublicLandingController.java:112-117` | работает |
| 10. ML резолвит `creative_id` по `meta_ad_map` | `ad_map_service.py:76-100`; `decide.py:150-156` | работает; DRIFT/UNMANAGED-строки не исключаются (M-16) |
| 11. Сверка раз в час: UNMANAGED/DRIFT, «заморозка плеча» | `MetaAdReconcileScheduler.java:59-157` | помечает статусы; **заморозки нет** (M-16) |
| 12. REJECTED → `blocked_by_meta` → recovery brief | `ModerationProbeScheduler.java:285-301`; `BlockedCreativeRecoveryScheduler.java:135-175` | работает |
| 13. PREVIEW_ONLY запрет скачивания | `FileController.java:29,50-54` | не работает (S-1, M-17) |

### 4.3. Находки

| ID | Серьёзность | Где | Что не так | Доказательство | Как исправить |
|---|---|---|---|---|---|
| M-1 | **P0** | `MetaAudienceController.java:56-175` | `POST /meta/audiences/sync` создаёт Custom Audience и заливает хэши email **мимо `MetaWriteGate`** и мимо `AutonomyService`. По политике `AUDIENCE_UPDATE` требует стадии FULL (`MetaWritePolicy.java:111-112`), здесь работает при READ_ONLY любому не-VIEWER. Использует CAPI-токен пикселя (`:86 capi_access_token_enc`), а не Marketing-токен; сегменты по `cluster_label` без фильтра `self_accredited` (нарушение §8 политики); `upsertCustomAudience` создаёт новую аудиторию при каждом вызове (`MetaApiService.java:166-173`, комментарий «idempotent by name» неверен) | В контроллере нет `metaWriteGate`/`autonomyService`; `MetaWriteGuardTest.java:23-24` regex не включает `upsertCustomAudience` | Добавить `upsertCustomAudience` в `WRITE_CALL` теста; `metaWriteGate.assertAllowed(tenantId, Request.of(AUDIENCE_UPDATE, …))`; брать `meta_access_token_enc`; хранить `audience_id` и переиспользовать; фильтр `self_accredited = true` |
| M-2 | **P0** | `SiteHealthScheduler.java:127-137`; `CvrCollapseScheduler.java:117-127`; `SiteHealthResumeService.java:106-108` | «P0 autopause» при падении сайта / обвале CVR **не вызывает Meta**: Runnable делает только `UPDATE meta_ad_sets SET status='PAUSED'`. Алерт говорит «ad sets paused (P0)». Расход в Meta продолжается во время инцидента, БД рассинхронизирована с Meta. `RESUME_AFTER_INCIDENT` — тоже только БД | Тела лямбд содержат только `jdbc.update`; `metaApi` в этих классах не инжектирован | В Runnable вызывать `metaApi.setAdSetStatus(token, id, "PAUSED")` для каждого ACTIVE ad set через `MetaWriteGate.decide(PAUSE)` и только потом UPDATE; RESUME аналогично; добавить классы в `MetaWriteGuardTest` |
| M-3 | **P1** (проверить вживую) | `ModerationProbeMath.java:321-322,331-336`; `MetaApiService.java:366`; `Phase9JobsMathTest.java:104` | Объявление создано `status=PAUSED`, а `verdict("PAUSED", false) == APPROVED`. Если Meta для объявления с configured status PAUSED отдаёт `effective_status=PAUSED` независимо от стадии ревью, первый опрос через ≤30 мин ставит APPROVED и разрешает `activate` **до вердикта Meta** — probe становится фикцией. Комментарий класса написан для старой схемы «ad set на паузе» | Тест закрепляет `PAUSED → APPROVED` | Проверить вживую. Если PAUSED маскирует ревью: создавать объявление `ACTIVE` внутри PAUSED ad set (ревью видно как PENDING_REVIEW → ADSET_PAUSED), либо требовать для APPROVED явный признак |
| M-4 | **P1** | `CreativePublishService.java:161-162`; `MetaApiService.java:344-348`; `Creative.java:37` | Видео-публикация: `c.getThumbnailUrl()` всегда null — `thumbnail_url` ни в одном месте backend не записывается. Meta требует для `video_data` `image_url` или `image_hash` → `/adcreatives` вернёт 400, публикация любого VIDEO-креатива упадёт после успешного `/advideos` (сиротский video в аккаунте) | grep `setThumbnailUrl` — не вызывается | Извлекать кадр (ffmpeg в ml-service) и заливать через `uploadImage` → `image_hash` в `video_data`; прописывать `thumbnail_url` при загрузке креатива |
| M-5 | **P1** | `CreativePublishService.java:113-196` | Нет идемпотентности: повторный клик «Publish» (или ретрай фронта по таймауту) создаёт второе видео/креатив/объявление. Весь метод `@Transactional`: 3-4 HTTP-вызова Meta (загрузка видео может идти минуты) внутри транзакции; при падении INSERT после `createAd` объект в Meta создан, строки карты нет; сверка через час подхватит его как RECONCILED, `activate` навсегда 409 | `latestManaged` используется только в `activate/pause` | Проверка существующей не-ARCHIVED строки `meta_ad_map` для `(creative_id, adset_id)` → 409; advisory lock; HTTP вне транзакции; компенсирующее удаление в Meta при неудаче |
| M-6 | **P1** | `MetaAdReconcileScheduler.java:72-93, 130-133`; `LinkageHealthService.java:176-181, 189-196`; `MetaWriteGate.java:74-80` | Linkage health считает **все** UNMANAGED-строки, а сверка оставляет ручное объявление UNMANAGED, пока оно не ARCHIVED → любое старое **выключенное** ручное объявление делает health красным и блокирует `LAUNCH` всему тенанту навсегда. На первом прогоне ручные объявления шлют по алерту **на каждое** — для аккаунта с историей это сотни сообщений | `newStatus = ARCHIVED? : UNMANAGED` | В UNMANAGED учитывать только `effective_status = ACTIVE`; алерт — один сводный на прогон |
| M-7 | **P1** | `TokenRotationScheduler.java:57-72`; `MetaTokenExchange.java:154-166`; `MetaWriteGate.decide` | Ротация по возрасту (50/58 дней) рассчитана на long-lived user token, а политика §7 предписывает **System User token** (не истекает, `fb_exchange_token` неприменим). Итог: с 50-го дня ежедневный алерт EXPIRING, на 58-й день валидный токен помечается INVALID и автономия падает в OBSERVER. Обратное: «пауза записей при INVALID» не реализована — `MetaWriteGate.decide` не читает `meta_token_status` | — | `GET /debug_token` (`expires_at=0` → не ротировать); в `MetaWriteGate.decide` DENY непротективных записей при `meta_token_status='INVALID'` |
| M-8 | **P1** | `MetaApiService.java:40, 143-146, 448-451, 473-476, 491-494`; `CreativePublishService.java:60`; `MetaApiClient.java:26-38, 78-84` | `new RestTemplate()` без connect/read-таймаутов — в `publish` внутри транзакции. Обрабатывается только `HttpClientErrorException`: 5xx и сетевые ошибки уходят сырыми. Коды Meta 190, 17/4/32, 80004 не разбираются; `X-Business-Use-Case-Usage` не читается; ретраев нет. `MetaApiClient`: бакет фиксированный 100/ч, при исчерпании P1/P2 **молча отбрасываются** (только log.warn) | — | Общий `RestTemplate` с таймаутами (connect 10 с, read 60 с, `/advideos` 300 с); единый `handleMetaError`: 190 → INVALID, 4/17/32/80004 → backoff; неуспех в `meta_api_audit` |
| M-9 | **P1** | `AutopilotService.java:118-130`; `RecoveryDiagnosisService.java:148-151` | `executeAction` только вставляет строку `autopilot_actions status='EXECUTED'` — `PAUSE_BURNOUT` ничего не паузит в Meta, UI показывает «executed». `resumeBatch` закрывает CB-события в БД, не возобновляя в Meta. Обе ветки обходят gate (не вызывают `MetaApiService`, guard-тест их не видит) | `executeAction` не содержит `metaApi` | Либо исполнять через `MetaApiService` + gate, либо писать `status='RECOMMENDED'` |
| M-10 | **P1/P2** | `BudgetGuardService.java:83-92, 123-141`; `CircuitBreakerScheduler.java:171-176` | Budget Guard: `current_spend` обновляется только вручную (`updateSpend`), `MetaSyncScheduler` в guard'ы не пишет; срабатывание = только алерт «Pause in Ads Manager». «Circuit breaker каждые 5 мин» ничего не выключает и ничего сам не измеряет. Реальная пауза есть только у L3 | grep `setCurrentSpend` вне сервиса — пусто | Кормить guard'ы из `MetaSyncScheduler`; при triggered — `PAUSE` через gate |
| M-11 | **P1** (проверить вживую) | `MetaApiService.java:78-101` | Все ad set, создаваемые платформой, захардкожены `optimization_goal=REACH`, `billing_event=IMPRESSIONS`, без `promoted_object`. Никакая CAPI value-оптимизация платформенными ad set не используется; при objective `OUTCOME_LEADS` Meta, вероятно, отвергнет такой ad set | — | Параметризовать `optimization_goal` и `promoted_object {pixel_id, custom_event_type}` из `meta_pixels`/`capi_event_map` |
| M-12 | **P1** (тест) | `MetaWriteGuardTest.java:23-25, 39-41` | Guard-тест: не включает `upsertCustomAudience` (пропустил M-1); ловит только идентификаторы `metaApi`/`metaApiService`; `metaWriteGate.decide(` засчитывается даже если результат проигнорирован; окно 40 строк не ограничено телом метода; не видит записи мимо `MetaApiService` (M-2, M-9) | — | Включать все публичные не-get методы `MetaApiService` рефлексией; матчить любой идентификатор типа; ограничить окно телом метода |
| M-13 | P2 | `CAPIForwardingService.java:187-195` | `action_source` всегда `system_generated`; нет `fbc/fbp/client_ip_address/client_user_agent/event_source_url`. `FORM_SUBMIT → Lead` — факт на сайте (website). EMQ будет низким при том, что платформенные лендинги вообще не ставят пиксель (grep `fbq` по frontend — пусто) | — | Хранить `_fbc/_fbp`, IP, UA в `sessions`/`form_submissions`; для стадий на сайте `action_source=website` |
| M-14 | P2 | `CAPIForwardingService` | Нет `test_event_code` — чек-лист §7 («перед боевой отправкой — Test Events») невыполним без отправки в боевой пиксель. Синтетических отправок в прод-пиксель в коде **не найдено** | — | `tenants.settings.capi_test_event_code` → в body пока установлен |
| M-15 | P2 | `GhlSyncScheduler.java:335-345`; `AppointmentService` | Polling-sync (факты, пропущенные вебхуком: showed/noshow/won) **не отправляет CAPI** — `CALL_HELD`, пойманный опросом, никогда не станет `CompleteRegistration`. Политика §3 обещает «GHL webhook / polling» | grep `capi` в `AppointmentService` — пусто | Из `syncLocation` вызывать `funnelService.resolveStage` + `forwardByStage` с тем же `deterministicEventId` |
| M-16 | P2 | `MetaAdReconcileScheduler.java:110-116`; ml-service grep `publish_status|DRIFT|UNMANAGED` — пусто | «Плечо замораживается до решения человека» (creative-publishing §3) не реализовано: `publish_status=DRIFT/UNMANAGED` никто не потребляет; ML `ad_map_service` резолвит `creative_id` и для DRIFT-строк | `_SQL` фильтрует только `creative_id IS NOT NULL` | В `_SQL` добавить `AND status NOT IN ('DRIFT','UNMANAGED')`; в reward-пути исключать такие пары |
| M-17 | P2 | `FileController.java:29, 50-54`; `SecurityConfig.java:39` | `/files/**` permitAll → финальный рендер скачивается без авторизации по известному URL; PREVIEW_ONLY работает только при `app.storage.type=local` (и то не работает, S-1). Превью «с водяным знаком» в коде нет | — | Подписанные URL с TTL; для PREVIEW_ONLY — отдельный watermarked-рендер |
| M-18 | P2 | `MetaApiService.java:200-209` | `extractConversions`: `type.contains("lead")` суммирует `lead`, `onsite_conversion.lead_grouped`, `offsite_conversion.fb_pixel_lead` → двойной/тройной счёт конверсий в `meta_ad_sets.conversions_*` и CB-метриках | — | Точный whitelist `action_type` |
| M-19 | P2 | `CreativePublishService.java:442-445` и 7 других call-site | `meta_api_audit` пишется только в publish/activate/pause и token exchange, **только при успехе**, `response_code` всегда 200, `request_hash` null, `actor_type` «USER» даже при `userId==null`. Политика §7 «каждый вызов — строка в meta_api_audit» не выполняется | `userId == null ? "USER" : "USER"` | Перенести аудит внутрь `MetaApiService` (обёртка вокруг `post*` с кодом ответа, хэшем, длительностью, ошибкой) |
| M-20 | P2 | `CreativePublishService.java:225-230`; `MetaApiService.java:86` | `activate` ставит `ACTIVE` объявлению в ad set, который платформа всегда создаёт PAUSED → показов нет, UI показывает ACTIVE_IN_META; через час сверка переводит в PAUSED_IN_META без объяснений | — | В `activate` проверять `effective_status` ad set/кампании и предупреждать |
| M-21 | P2 | `GhlWebhookService.java:135-229` | `process` `@Transactional` оборачивает HTTP к Meta (CAPI) и GHL; повторная доставка вебхука не дедуплицируется (`ghl_webhook_log` без уникальности) — дубли `capi_events` и повторные отправки | — | Идемпотентность по `event_id` в `capi_events` (`ON CONFLICT DO NOTHING`); HTTP после коммита |
| M-22 | P2 (проверить вживую) | `MetaApiService.java:37`; `CAPIForwardingService.java:35`; `MetaTokenExchange.java:154` | Версия Graph API `v20.0` (май 2024); по 2-летнему циклу поддержка, вероятно, закончилась ≈ май 2026 — вызовы молча «апгрейдятся» на старейшую живую версию. `standard_enhancements` в новых версиях заменяется поимёнными фичами — OPT_OUT может перестать применяться | — | Версию в конфиг; проверить Changelog; поимённый OPT_OUT |
| M-23 | P2 | `MetaApiService.java:274-314`; `CreativePublishService.java:398-419` | Chunked upload: весь файл в `byte[]`, чанк 8 МБ, **повтора чанка нет**, `finish` считает успех по умолчанию (`asBoolean(true)`) | — | Стримить с диска, retry чанка ×3, явная проверка `success==true` |
| M-24 | P2 | `MetaWriteStageService.java:160-164` | Условие FULL «0 DENY за 7 дней» считает DENY от любого клика пользователя в запрещённое действие — одна ошибка UI откладывает FULL на неделю | — | Считать только DENY инициатора JOB |
| M-25 | P2 | `MetaWriteGate.java:129-137` | Override `action='*'` не учитывает инициатора — его может «съесть» джоба, а не человек, для которого он выдан | — | `initiator='USER'` как условие применения override |
| M-26 | P3 | `GhlWebhookController.java:39`; `GhlSubaccountController.java:433-443` | Секрет вебхука принимается query-параметром `secret` (попадает в access-логи); `webhook_secret` хранится открыто | — | Только заголовок; хранить хэш |
| M-27 | P3 | `CAPIForwardingService.java:237-239` | Телефон нормализуется до цифр без E.164 → ниже EMQ по `ph` | — | libphonenumber с регионом тенанта |
| M-28 | P3 | `MetaApiService.java:382-404` | `listAds` ограничен 20×200 = 4000 объявлений молча; нет фильтра `effective_status` | — | Логировать усечение; явный список статусов |
| M-29 | P3 | `MetaWriteAction.BUDGET_CHANGE` | Класс BUDGET_CHANGE и пороги бюджета — без пути исполнения: в `MetaApiService` нет метода изменения `daily_budget` | — | Реализовать или отметить в доке как «не реализовано» |
| M-30 | P3 | `GoogleAdsApiService.java:114+`; `TikTokApiService` | Методы записи (`setCampaignStatus`) — мёртвый код без gate и без флага | — | Удалить или подвести под gate |
| M-31 | P3 | `CreativePublishService.java:94-99, 394-396` | Readiness при каждом `publish-preview` читает весь файл с диска (`readFile(c).length`) | — | `Files.size` |

### 4.4. Статус замечаний ревью 2026-10-20

| # | Замечание | Статус |
|---|---|---|
| 12 | Ротация токена — заглушка | **Закрыто**: реальный `fb_exchange_token`, `meta_api_audit`, OBSERVER при INVALID, восстановление при re-auth. Новое: M-7 |
| 14 | OPE-gate при апгрейде автономии | **Закрыто**: `AutonomyController.put:543-551` + `AutonomyGate.evaluate`; `OpeScheduler` еженедельно; FULL-стадия требует OPE PASS |
| 11 | `predicted_value` не уходил в CAPI | **Частично**: механизм есть (`ValueFormulaService:58-75`), но дефолтный seed формулы `predicted_value` не содержит — попадёт в CAPI только после ручной правки `capi_event_map` |
| 10 | Карта CAPI расходилась с документом | **Закрыто**: `seedInvestorCAPIMap:168-173` совпадает с architecture §16.3 и vision §19.4 |
| 16 | Бакет не из `X-Business-Use-Case-Usage` | **Открыто** (M-8) |

### 4.5. Проверить вживую с реальным аккаунтом

1. Семантика `effective_status` у объявления с `status=PAUSED` в ревью (M-3) — от этого зависит, работает ли probe-модерация вообще.
2. `/adcreatives` с `video_data` без `image_url/image_hash` (M-4) — ожидается 400.
3. Срок жизни v20.0 и судьба `standard_enhancements` OPT_OUT (M-22).
4. `optimization_goal=REACH` + objective кампании (M-11).
5. `GET /act_X/ads` без фильтра `effective_status` — включаются ли ARCHIVED/DELETED.
6. `fb_exchange_token` для System User token (M-7).
7. Имена событий GHL (`CAPIForwardingService:55` сам помечен «verify exact event name»).
8. Дедупликация CAPI с браузерным пикселем: на платформенных лендингах пиксель не ставится; если клиент ставит свой, `event_id` другой → двойной `Lead`.
9. EMQ с `system_generated` без fbp/fbc/IP — измерить в Events Manager через 24 ч.

### 4.6. Итог по области

Каркас политики записей сделан добросовестно: gate стоит перед 10 из 11 вызовов `MetaApiService`, защитные паузы не блокируются, стадии переходят по данным, override журналируется, ротация токена и OPE-gate закрыты. Две дыры уровня P0: синхронизация Custom Audience пишет в Marketing API мимо gate, политики и guard-теста; «P0-автопауза» при падении сайта и обвале CVR меняет только статус в БД и не трогает Meta — расход продолжается, пока алерт сообщает о паузе. Сквозной сценарий Phase 11 рвётся в двух местах: видео-креатив не опубликуется (нет thumbnail), а probe-модерация, вероятно, ставит APPROVED объявлению на паузе до вердикта Meta. Карта `meta_ad_map` и резолв `mai_c → creative_id` работают, но «заморозка плеча» при DRIFT/UNMANAGED не реализована, а блокировка LAUNCH ложно срабатывает на любом старом выключенном ручном объявлении. CAPI безопасен в главном, но `action_source` и отсутствие fbp/fbc/IP ударят по EMQ, polling-факты в CAPI не попадают, `test_event_code` не поддержан. Надёжность внешнего слоя слабая: `RestTemplate` без таймаутов внутри транзакций, нет разбора кодов Meta, нет идемпотентности публикации, аудит пишется только при успехе.

---

## 5. Слой данных и фоновые джобы (platform-api)

**Охват.** Прочитаны полностью все 136 миграций (4074 строки), все 21 JPA-сущность, все 21 репозиторий, все 56 классов `scheduler/` (5212 строк), `config/`, `security/`, `application.yml` + `application-local.yml`, `docker-compose.yml`, `build.gradle.kts`. По трассам из шедулеров и hot-path: `EventIngestionService`, `EventController`, `PublicLandingController`, `VariantService`, `TenantDomainService`, `CAPIForwardingService`, `MetaApiClient`, `SiteHealthResumeService`, `GateEvaluationService`, `BudgetGuardService`, `CircuitBreakerService`, `CreativePublishService`, `GhlWebhookService`, `FormSubmissionService`, `LandingSetService`, `RewardService`, `FraudScoringService`, `AnalyticsService`, `AlertService`, `AuthService`, `ErasureController`, `EventPartitionMath`, ml-service `decide.py` (hot-path), `.ai/architecture.md §9`.

### 5.1. Таблица шедулеров (56)

Общее: все выполняются в пуле `sched-` из **4 потоков** (`SchedulingConfig.java:30`), каждый `Runnable` обёрнут в `TenantContext.runAsSystem` → `app.rls_bypass='on'`; **ни одного `@SchedulerLock`/ShedLock в проекте** → при двух репликах каждая джоба выполняется дважды. `cron` без `zone` = JVM default.

| Класс | Расписание | Обход тенантов | Исключения | Внешние вызовы | Замечание |
|---|---|---|---|---|---|
| AppointmentCheckScheduler | fixedDelay 30 мин | глобальный (bypass) | `log.error` | — | одна `@Transactional` на все pending всех тенантов |
| AutopilotScheduler | cron 00 * * * (JVM TZ) | `autopilot_settings.enabled` | per-tenant `warn` | AutopilotService | **выключен по умолчанию** (V40) |
| BlockedCreativeRecoveryScheduler | fixedDelay 6 ч | кросс-тенантный `LIMIT 50` | per-row `warn` | ML `/brief/generate` | идемпотентен |
| BurnoutScanScheduler | cron 06:00 (JVM TZ) | тенанты с `meta_insights_history` 7 дн | per-tenant `warn` | ML burnout, alert | **фактически idle**: `meta_insights_history` пишет только ручной `SaturationController` (D-10); ежедневный новый бриф без дедупа (D-23) |
| CapiRetryScheduler | fixedDelay 10 мин | глобально `LIMIT 200` | `warn` | Meta CAPI | ОК |
| CapiValueModeScheduler | 1 ч + Mon 07:15 UTC | `capi_value_mode='AB'` | per-tenant `warn` | CapiValueModeService | выключен по умолчанию |
| ChurnPenaltyScheduler | cron 03:00 (JVM TZ) | в сервисе | `error` | ML | совпадает по времени с Retro/NicheBenchmark |
| CircuitBreakerScheduler | 5 мин (guards) + 30 мин (L2/L3) | guards: глобально; L2/L3: все | guards: **без try/catch** | Meta `setCampaignStatus` (только L3), alert | **L1 budget guard мёртв** (D-3) |
| ClusterFitScheduler | cron Sun 03:00 UTC | все | per-tenant `error` | ML `/cluster/fit` | **N+1** по сессиям за 30 дн без лимита (D-19) |
| CompetitiveIntelligenceScheduler | cron Mon 05:00 (JVM TZ) | в сервисе | `error` | Meta Ad Library + LLM | — |
| CrmDisciplineScheduler | cron Mon 08:00 (JVM TZ) | все | per-tenant `warn` | alert | `avg_quality_score` всегда NULL (D-2); отчёты всех тенантов в один чат |
| CvrCollapseScheduler | fixedDelay 15 мин | все | per-tenant `warn` | ML, alert, «P0 pause» | **пауза только в БД** (D-1) |
| DiagnosticsBaselineScheduler | cron Sun 05:00 UTC | все | per-tenant `error` | — | `NOT EXISTS` по `events` на каждую сессию за 90 дн |
| EntropyScheduler | cron Mon 07:00 UTC | ниши ≥5 тенантов | per-niche `error` | ML, alert | ниша из `settings->>'niche'` (D-24) |
| EventPartitionScheduler | startup + 1-го 00:10 UTC | — | per-partition `warn` | — | через SECURITY DEFINER (V128); текущий месяц +2 |
| ExperimentMonitorScheduler | fixedDelay 1 ч | ACTIVE experiments | per-row `warn` | ExperimentService | — |
| ExternalSignalsScheduler | 1 ч (CPM) + 06:00 (погода) + 06:30 (новости) | — | `warn` | Open-Meteo, ML | гео погоды **захардкожено «51.5,-0.1» (Лондон)** (D-21); anomaly-scan из §9 не реализован |
| FollowupAutoPauseScheduler | fixedDelay 1 ч | тенанты с APPROVED followup | `warn` | ML | N+1 |
| FormHealthScheduler | fixedDelay 30 мин | **`LIMIT 50` без ORDER BY** | `warn` | ML, alert | при >50 лендингов часть не проверяется никогда (D-22) |
| FormNoSlotScheduler | fixedDelay 1 ч | тенанты с FORM_NO_SLOT | `warn` | ML | идемпотентен |
| FraudL2Scheduler | fixedDelay 1 ч | все | `warn` | alert | ENFORCE-пауза только в БД; **по умолчанию LOG_ONLY** |
| FraudL3Scheduler | fixedDelay 1 ч | все | `warn` | alert | все CLICK/PAGE_VIEW за 24 ч в память; нет индекса `(tenant_id,event_type,event_time)` |
| FraudReviewScheduler | fixedDelay 1 ч | в сервисе | `error` | — | — |
| FunnelIntegrityScheduler | cron 07:15 (JVM TZ) | все | `warn` | alert | upsert — ОК |
| GateScanScheduler | fixedDelay 1 ч | все | `warn` | — | `kill()` = `setStatus("PAUSED")` только в БД (D-1) |
| GhlSyncScheduler | fixedDelay 1 ч | MAIN sub-аккаунты с ключом | `warn` | GHL API | выключен, пока нет ключа |
| GraveyardReviewScheduler | cron Mon 08:00 (JVM TZ) | тенанты с postmortems | `warn` | ML | зависит от `anchor_divergence_log`, **в который никто не пишет** (D-10) |
| LearningPhaseCheckScheduler | fixedDelay 30 мин | глобально `LIMIT 100` | `warn` | Meta | — |
| MatchScoreCalibrationScheduler | cron 1-го 07:00 UTC | **один запрос по всем тенантам** | `error` | ML, alert | смешивает тенантов, `LIMIT 50000` без ORDER BY (D-19) |
| MetaAdReconcileScheduler | fixedDelay 1 ч | тенанты с токеном | `warn` | Meta `listAds` | карта и `publish_status` отдельными autocommit (D-22) |
| MetaSyncScheduler | fixedDelay 30 мин | ACTIVE кампании | `warn` | Meta insights | синхронизирует только `status='ACTIVE'` адсеты → запаузенные-в-БД перестают синкаться |
| MmfScanScheduler | cron 05:30 UTC | ACTIVE кампании | `warn` | ML, alert | — |
| ModerationProbeScheduler | fixedDelay 30 мин | глобально `LIMIT 200` | `warn` | Meta, alert | три autocommit-UPDATE без транзакции |
| NicheBenchmarkScheduler | cron 03:00 UTC | один кросс-тенантный запрос | `error` | ML | **декартово произведение** (D-4) |
| NicheFeatureStoreScheduler | cron 1-го 06:00 UTC | один запрос | **нет try/catch** | — | тяжёлые `NOT EXISTS` по events 90 дн |
| NoshowAttributionScheduler | cron Mon 05:30 (JVM TZ) | все | `warn` | ML | — |
| OpeScheduler | cron Mon 05:00 UTC | все | `warn` | OpeService, alert | пишет `autonomy_level` |
| PersonaPromotionScheduler | cron Mon 07:30 UTC | `persona_mode` | `warn` | PersonaModeService | — |
| PixelPollutionScheduler | cron 04:00 (JVM TZ) | в сервисе | `error` | alert | совпадает с TokenRotation |
| PosteriorRecalcScheduler | fixedDelay 15 мин | глобально `LIMIT 200` | нет | — | **no-op**: тяжёлый JOIN ради `log.debug` (D-10) |
| ProxyCalibrationScheduler | cron Mon 03:00 (JVM TZ) | все | `warn` | — | требует `quality_score` ≥30 строк → **всегда «insufficient»** (D-2) |
| QualityScoreScheduler | fixedDelay 15 мин | глобально `LIMIT 5000` | `warn` | — | **противоречивое окно → никогда не скорит** (D-2) |
| RedFlagsScanScheduler | fixedDelay 30 мин | варианты ≥50 сессий | `warn` | ML, alert | ниша из `settings` |
| RegCfWaitlistScheduler | cron Mon 06:00 UTC | все | `warn` | ML | upsert — ОК |
| RetentionScheduler | cron 1-го 02:00 (JVM TZ) | глобально | **нет try/catch, не транзакционен** | — | **упадёт на FK при DELETE sessions** (D-5); DELETE вместо DROP PARTITION |
| RetroCorrectionJob | cron 03:00 UTC | все | `error` | — | 3 запроса на каждый `external_id_hash` |
| RewardSweepScheduler | fixedDelay 1 ч | в ML | `warn` | ML sweep | ОК |
| SelfReportCalibrationScheduler | cron Mon 06:30 UTC | все | `warn` | — | — |
| ShowRateCorrectorScheduler | cron Mon 06:00 (JVM TZ) | все | `warn` | ML | — |
| SiteHealthScheduler | fixedDelay 60 с | ACTIVE лендинги с `base_url` | **`jdbc.update` вне try** | HTTP HEAD **без таймаута**, alert, «P0 pause» | пауза только в БД (D-1); 1440 строк/сут/лендинг без ретеншена (D-20) |
| SlotCapacityScheduler | fixedDelay 15 мин | `slot_capacity_policies.enabled` | `warn` | — | **выключен по умолчанию** (V107) |
| StageValueRefreshScheduler | cron Mon 07:00 UTC | все | `warn` | — | `COUNT(DISTINCT session_id)` по events 180 дн на стадию |
| TaxonomyRecalibrateScheduler | cron Sun 04:00 UTC | в ML | `error` | ML | — |
| TokenRotationScheduler | cron 04:00 (JVM TZ) | тенанты с токеном | `error` | Meta, alert | `meta_token_created_at` ставится только при сохранении через `TenantService` |
| VideoLifecycleScheduler | cron 02:00 (JVM TZ) | глобально | нет | — | только флаг `COLD`, в R2 ничего не переносится |
| WeeklyReportScheduler | cron Mon 08:00 UTC | `findAll()` + `TenantContext.set` | `error` | ML, Telegram/Slack, SMTP | **первый тенант считается под bypass → отчёт по всем тенантам** (D-7) |

Выключены флагом по умолчанию: Autopilot, CapiValueMode, SlotCapacity, FraudL2 ENFORCE, GhlSync (нужен ключ), MetaAdReconcile/TokenRotation/MetaSync (нужен токен), FormHealth (нужен `form_endpoint_url`), SiteHealth (нужен `base_url`), ExternalSignals-news (нужны `news_feeds`), FormNoSlot/FollowupAutoPause (нужны follow-up варианты).

### 5.2. Миграции и сущности

- **Таблиц:** 141 `CREATE TABLE` в V1–V136 (без 6 предсозданных партиций `events`). С `tenant_id` — 130; без — 11 (`tenants`, `campaign_cpm_history`, `event_schemas`, `trademark_watchlist`, `atom_registry`, `niche_entropy_scores`, `niche_benchmarks`, `compliance_rulebases`, `niche_metric_benchmarks`, `agency_accounts`, `agency_kpi_snapshots`).
- **RLS:** V116 циклом навесил `ENABLE+FORCE` и политику `(tenant_id = current [OR tenant_id IS NULL]) OR app.rls_bypass='on'` на все таблицы с `tenant_id` (включая партиции); все таблицы после V116 имеют явную политику. Исключение: `atom_registry_tenants` (V119). Новые партиции получают политику в `ensure_events_partition`.
- **Гранты:** `V116:61-62 ALTER DEFAULT PRIVILEGES … GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO mai_app` (и V127 для `mai_ml`) → все узкие гранты в поздних миграциях (`V133:37`, `V134:31`, `V135:49`) **не действуют**. Инвариант «append-only `meta_api_audit`» (V47) снят без REVOKE. Реально ограничены только `thompson_arms`, `events`/`decision_log` (без UPDATE), `ghl_webhook_log` (без DELETE).
- **Холостое повторное создание колонок** (`IF NOT EXISTS`, безопасно): `sessions.external_ctx` V50/V91; `sessions.bounce_corrected_at` V50/V71; `landing_variants.approved_by/approved_at` V3/V92; `creatives.exploration_prior_*` V20/V132; `variant_offers.estimated_margin` V114/V132. V54 — пустой файл.
- **Опасных DROP/DELETE нет.** `V128:14 UPDATE events SET schema_version=1` — полный проход по партициям.
- **Сущности ↔ схема (21):** расхождений, роняющих `validate`, не найдено. Подозрения: примитивы на nullable-колонках — `Creative.isAnchor`, `BudgetGuard.currentSpend`/`triggered`, `MetaPixel.defaultPixel` — NULL из SQL даст исключение при загрузке.
- **`ddl-auto`:** `application.yml:9 validate`, но `application-local.yml:11 none`, а профиль `local` активен по умолчанию и единственный с `datasource` → **`validate` никогда не выполняется** (D-16).
- **FK без индекса** (16): `variant_offers.variant_id`, `landing_variants.creative_id`, `meta_ad_sets.creative_id`, `meta_campaigns.pixel_id`, `thompson_reward_log.arm_id`, `churn_penalty_log.arm_id`, `sessions.funnel_path_id`, `landing_variants.funnel_path_id`, `followup_assignments.variant_id`, `persona_scores.persona_id`, `call_recordings.session_id`, `value_calibration_log.session_id`, `regcf_waitlist.source_session`, `experiment_events.experiment_id`, `channel_campaigns.channel_account_id`, `moderation_results.ad_set_id`, `runner_keys.domain_id`.
- **Индексы под шедулеры:** `events` имеет только `(tenant_id, session_id, event_time)`, `(tenant_id, variant_id, event_type, event_time)` и partial-unique дедуп; запросы по `(tenant_id, event_type, event_time)` (FraudL3, StageValueRefresh, PosteriorRecalc, Analytics) и по `sessions.last_event_at` без индекса.
- **Партиции `events`:** V5 до 2027-03; `EventPartitionScheduler` создаёт текущий месяц +2 через SECURITY DEFINER; сбой — только `log.warn`, без алерта.

### 5.3. Инварианты, подтверждённые кодом

1. Единственный писатель `thompson_arms` — ML (`V116:65`; в Spring нет `save` по `ThompsonArm`).
2. Все `@Scheduled` под `app.rls_bypass='on'` через `SystemScopedTaskScheduler`; GUC перед каждым statement и сброс при `close()` — стейл-тенант в пуле невозможен.
3. HTTP-запросы: `JwtFilter` ставит tenant и `finally clear()`; публичные bootstrap-пути явно используют `callAsSystem`.
4. Ingestion: `event_id` обязателен, серверная валидация владельца variant/creative, `revenue` обнуляется, события пишутся одним `batchUpdate` (`EventIngestionService.java:279`), partial-unique индекс покрывает дедуп в БД.
5. ML-ключ добавляется интерцептором ко всем вызовам на `ml-service-url`.
6. CAPI-retry: `attempts/last_attempt_at` (V118), ≤7 дней, `MAX_ATTEMPTS`.
7. SQL-инъекций через конкатенацию не найдено: все конкатенации — константы или белые списки. Подозрение: `GoogleAdsApiService.java:79-80` вклеивает `resourceName` и `dateRangeType` в GAQL.

### 5.4. Находки

| ID | Серьёзность | Где | Что не так | Доказательство | Как исправить |
|---|---|---|---|---|---|
| D-1 | **P1** (= M-2) | `SiteHealthScheduler.java:127-137`, `CvrCollapseScheduler.java:117-127`, `GateEvaluationService.java:354-362`, `FraudL2Scheduler.java:111-114`, `SiteHealthResumeService.java:106-107` | «P0 autopause», kill на гейтах, ENFORCE-пауза и resume **никогда не доходят до Meta** — меняется только `meta_ad_sets.status`; затем `MetaSyncScheduler` синхронизирует только `status='ACTIVE'`, т.е. «запаузенный» адсет перестаёт обновлять spend, продолжая крутиться в Meta | grep `setAdSetStatus` — единственный вызов в `TwoContourService.java:377` | Внутри лямбды вызывать `metaApi.setAdSetStatus` через `MetaWriteGate` как PROTECTIVE; тест, что путь P0 вызывает Meta |
| D-2 | **P1** | `QualityScoreScheduler.java:31,53-54` | Окно выборки противоречиво: `cutoff = now-16min`, условие `last_event_at < now()-30min AND last_event_at >= cutoff` — пустое множество → `sessions.quality_score` **никогда не вычисляется**; каскадом мертвы ProxyCalibration (`<30 строк`), `creative_quality`, CrmDiscipline `avg_quality_score`, clickbait-детект, компонент QS в WeeklyReport | единственный писатель `quality_score` — этот UPDATE (`:69`) | `last_event_at < now()-30min AND quality_score IS NULL AND last_event_at > now()-24h`; индекс `sessions(last_event_at) WHERE quality_score IS NULL`; одновременно D-6 |
| D-3 | **P1** (= M-10) | `CircuitBreakerScheduler.java:23-27` + `BudgetGuardService.java:98-140` | L1 budget guard сравнивает `current_spend >= threshold`, но `current_spend` пишут только ручной `updateSpend` и месячный сброс; `MetaSyncScheduler` в guards не пишет → **L1 никогда не сработает автоматически**; при срабатывании только alert | grep `setCurrentSpend|current_spend` → только BudgetGuardService | Агрегировать spend в `budget_guards.current_spend` из `MetaSyncScheduler`; при триггере — пауза через gate |
| D-4 | **P1** | `NicheBenchmarkScheduler.java:48-51` | `tenants LEFT JOIN appointments LEFT JOIN reward_events LEFT JOIN form_submissions` без связи между дочерними таблицами → декартово произведение `a×re×fs` на тенанта (при 1k/5k/3k строк ≈ 1.5·10¹⁰ промежуточных строк) ежедневно в 03:00 UTC | текст запроса | Три коррелированных подзапроса или отдельные агрегаты по `tenant_id` |
| D-5 | **P1** | `RetentionScheduler.java:66-68`, `ErasureController.java:71-73` | `DELETE FROM sessions` при 14 FK **без `ON DELETE`** (`reward_events`, `quality_gate_log`, `form_submissions`, `value_calibration_log`, `micro_conversion_events`, `touchpoints`, `attribution_credits`, `path_gate_log`, `noshow_scores`, `lead_value_predictions`, `call_notes`, `investor_archetypes`, `regcf_waitlist`, `call_recordings`) → FK violation. Retention упадёт на 13-м месяце после того, как `events` уже удалены (не транзакционно); GDPR-erasure падает 500 уже сейчас для любого лида с формой или наградой | grep `REFERENCES sessions` без `ON DELETE` = 14, с = 0 | Миграция `ON DELETE CASCADE/SET NULL`; retention — `DROP TABLE events_YYYY_MM` вместо `DELETE`, транзакция |
| D-6 | **P1** (латентно) | `QualityScoreScheduler.java:102-126` + V50 | `INSERT INTO creative_quality … ON CONFLICT DO NOTHING` — у таблицы нет UNIQUE → конфликт невозможен, каждый прогон добавляет строку (до 96/сут на креатив); `BurnoutScanScheduler` UPDATE бьёт по всем дублям. Не проявляется только потому, что D-2 отключает ветку | V50/V51/V79 без UNIQUE | `CREATE UNIQUE INDEX … ON creative_quality(tenant_id, creative_id, window_key)` + `DO UPDATE` |
| D-7 | **P1** | `WeeklyReportScheduler.java:60-65` + `TenantContext.set()` | Джоба под `runAsSystem`; `TenantContext.set(id)` ставит только TENANT и не снимает SYSTEM → для **первого тенанта в `findAll()`** scope = `(tenant, bypass='on')`, политика пропускает все строки; `AnalyticsService.overview()` не фильтрует по `tenant_id` → отчёт первого тенанта содержит сессии/конверсии/выручку **всех тенантов** и уходит в Telegram/e-mail. После `clear()` остальные считаются корректно | `TenantContext.java:30-31` `TENANT.set(tenantId)` без `SYSTEM.remove()`; `:44` `clear()` снимает SYSTEM | Использовать `TenantContext.runAs(tenantId, …)`; тест «bypass выключен внутри runAs» |
| D-8 | **P1** | `EventIngestionService.java:81-86,189-197` + `application.yml:17-19` | Redis — обязательная зависимость ingestion без degradation: при недоступном Redis каждый батч `/events` отвечает 500; таймауты Lettuce не настроены (60 с) → Tomcat-потоки висят. `markSeen` выполняется **до** записи в БД: при падении `writeEvents` повтор от клиента отброшен как дубликат | нет `spring.data.redis.timeout`; `isDuplicate` без try/catch | Дедуп в try/catch с деградацией на partial-unique в БД (+ `ON CONFLICT DO NOTHING`); `markSeen` после записи; `timeout: 500ms` |
| D-9 | **P1** (= M-8) | `RestClientConfig.java:22`, `MetaApiService.java:40`, `CreativePublishService.java:60`, `GoogleAdsApiService.java:28`, `TikTokApiService.java:28` | Все `RestTemplate` без connect/read таймаутов; единственные таймауты — в `RecaptchaFilter`. Зависший HEAD лендинга (SiteHealth каждые 60 с), ML, Meta, Telegram навсегда занимает один из **4** sched-потоков и Hikari-соединение | тексты конфигов | `RestTemplateBuilder.setConnectTimeout(3s).setReadTimeout(15–60s)`; один общий бин |
| D-10 | P2 | `BurnoutScanScheduler.java:34-38`, `GraveyardReviewScheduler.java:79-92`, `PosteriorRecalcScheduler.java:29-44` | Джобы, зависящие от таблиц без писателей: `meta_insights_history` пишет только ручной `SaturationController` → BurnoutScan обходит 0 тенантов; `anchor_divergence_log` не пишет никто → Graveyard/Burnout без anchor-вердикта; PosteriorRecalc — тяжёлый JOIN раз в 15 мин ради `log.debug` | grep `INSERT INTO anchor_divergence_log` → 0 файлов | Писать `meta_insights_history` из `MetaSyncScheduler`; реализовать anomaly-scan или убрать; удалить PosteriorRecalcScheduler |
| D-11 | P2 | `MetaApiClient.java:37-38,67-84,118-128` + V70 | «Приоритетная очередь» синхронна: `p1Queue`/`p2Queue` не используются; `logCall` пишет в `learning_phase_log` с `action='AUTOPAUSE_*'` и `ad_set_id=NULL` → нарушает CHECK и NOT NULL → вставка всегда падает и глотается → **аудит P0-пауз не существует** | `V70:7,9`; `log.trace` в `:126` | Писать аудит в `meta_api_audit`; не глотать ошибку |
| D-12 | P2 | `CreativePublishService.java:113-164`; `GhlWebhookService.java:38,101,125,274`; `FormSubmissionService.java:42,100`; `LandingSetService.java:36,149`; `TenantDomainService.java:35,80,86,156`; `CircuitBreakerService.java:248,269`; `BudgetGuardService.java:98,135` | Внешние HTTP/DNS внутри `@Transactional`: загрузка видео в Meta + 3 вызова в одной транзакции; CAPI, ML и GHL внутри обработки вебхука; GHL внутри `/public/form/submit`; LLM-генерация набора лендингов; DNS в `verify()`. При Hikari по умолчанию **10 соединений** и D-9 пул исчерпывается несколькими медленными вызовами. `tenantForRunnerKey` делает UPDATE `last_seen_at` **на каждый `/public/decide`** внутри транзакции | аннотации по строкам | Вынести внешние вызовы за транзакцию; `last_seen_at` раз в N минут; задать `hikari.maximum-pool-size`, `connection-timeout`, `leak-detection-threshold` |
| D-13 | P2 (= S-6) | `LandingVariantRepository`, `VariantService.java:236-241`, `PublicLandingController.java:40` | Публичный `/l/{slug}` без `host` ищет slug глобально под bypass; уникальность `(tenant_id, slug)` → 500 при коллизии | — | `findFirst…` + алерт либо обязательный `host` |
| D-14 | P2 (= S-22) | `UserRepository`, `AuthService.java:46,70` | Логин/регистрация ищут e-mail глобально, уникальность с V22 — `(tenant_id, email)` | V22:9 | `findByEmailAndTenant…` либо глобальная уникальность |
| D-15 | P2 | `EventIngestionService.java:294-295` + `EventDto.java:48` | `event_time` берётся с клиента без ограничений; значение вне диапазона партиций (2020 или 2028) даёт `no partition of relation "events"` и роняет весь batch; дубликат `event_id` после истечения Redis-ключа → unique violation → тоже весь batch | схема V5/V24; нет `ON CONFLICT` в `writeEvents` | Клампить `event_time` в `[now-7d, now+5min]`; `ON CONFLICT DO NOTHING` |
| D-16 | P2 | `application.yml:5,9`, `application-local.yml:11` | `ddl-auto: validate` переопределён `none` в единственном рабочем профиле → дрейф entity↔schema ловится только в рантайме; профиля для прода нет | тексты yml | Убрать `ddl-auto: none` из local; вынести datasource в `application.yml` |
| D-17 | P2 | `V116:59-62` vs `V133:37`, `V134:31`, `V135:49`, `V47:25`, `V115:23` | `ALTER DEFAULT PRIVILEGES … GRANT … UPDATE, DELETE` делает все последующие «append-only» гранты декоративными; `meta_api_audit`, `meta_write_decisions`, `render_feedback`, `thompson_reward_log` обновляемы/удаляемы из приложения | явные REVOKE только V116:65-70, V127:28-30 | Миграция с `REVOKE UPDATE, DELETE` на журналах |
| D-18 | P2 | все 56 классов, `build.gradle.kts` | Нет ShedLock/лидер-выбора: при ≥2 репликах удваиваются алерты, брифы, penalty, параллельный Retention | grep `shedlock` пуст | `shedlock-spring` + `@SchedulerLock` |
| D-19 | P2 | `ClusterFitScheduler.java:78-83`; `FraudL3Scheduler.java:119-123`; `MatchScoreCalibrationScheduler.java:40-51`; `RetroCorrectionJob`; `FollowupAutoPauseScheduler` | N+1 и безлимитные выборки: ClusterFit — запрос `events` на **каждую** сессию за 30 дн; FraudL3 — все CLICK/PAGE_VIEW за 24 ч в память ежечасно; MatchScore — `LIMIT 50000` без ORDER BY и без разбиения по тенантам; RetroCorrection — 3 запроса на каждый hash | тексты | `jsonb_agg` + `LIMIT`; индекс `events(tenant_id, event_type, event_time)`; MatchScore по тенанту |
| D-20 | P2 | `SiteHealthScheduler.java:89`; `ExternalSignalsScheduler.java:109-111` | Нет ретеншена для `site_health_checks` (1 440 строк/сут/лендинг), `external_signals`, `capi_events`, `meta_api_audit`, `events_rejected`, `learning_phase_log`, `niche_metric_benchmarks` (INSERT без upsert ежедневно); `RetentionScheduler` чистит только events/sessions/decision_log | — | Добавить в sweep |
| D-21 | P2 | `ExternalSignalsScheduler.java:64` | Погода для захардкоженной точки «51.5,-0.1» (Лондон) как платформенный сигнал для всех тенантов; для клиента в США бессмысленно | текст | Гео из `tenants.settings` |
| D-22 | P2 | `FormHealthScheduler.java:56`; `MetaAdReconcileScheduler.java:110-116,165-172`; `ModerationProbeScheduler.java:76-92` | `LIMIT 50` без ORDER BY — при >50 лендингов часть не проверяется никогда; `meta_ad_map.status` + `creatives.publish_status` обновляются отдельными autocommit — при сбое между ними карта и креатив расходятся | тексты | `ORDER BY last_check_at NULLS FIRST`; `TransactionTemplate` |
| D-23 | P2 | `BurnoutScanScheduler.java:171-187` | При `days_remaining ≤ 7` ежедневно новый `creator_briefs` без проверки существующего `replacement_brief_id` → 7 дублей за неделю (когда D-10 будет починен) | текст | Проверять `replacement_brief_id IS NULL` |
| D-24 | P3 | `EntropyScheduler.java:46`, `NicheFeatureStoreScheduler.java:33`, `RedFlagsScanScheduler.java:28` vs `NicheBenchmarkScheduler` | Три источника «ниши»: `tenants.niche`, `tenants.niche_key`, `settings->>'niche'` | тексты | Один канонический столбец |
| D-25 | P3 | `VariantService.java:379` vs `thompson_service.py:129-133` | Кабинет читает плечо с ключом `global`, ML создаёт `channel:META` → `alpha/beta/totalSamples` в ответах Spring `null` для новых тенантов | тексты | Читать `channel:META` с fallback |
| D-26 | P3 | 20 cron-джоб без `zone` | Комментарии обещают UTC, расписание — JVM default; пики совпадений: 03:00 (до 5 джоб на 4 потока вместе с D-4), Mon 08:00 | `grep @Scheduled` | `zone="UTC"`; развести по минутам |
| D-27 | P3 | `VideoLifecycleScheduler`; `architecture.md §9` | §9: «Drop raw events partitions» (реализован DELETE), «Quality Score → FastAPI» (в Spring), «Budget guard → Marketing API write» (только alert), «55 scheduler classes» (56); `VideoLifecycle` только флаг `COLD` | тексты | Синхронизировать §9 |

### 5.5. Производительность и рост данных

Профиль пилота: 1–3 тенанта, 8 vCPU/32 GB, Hikari **10** соединений (дефолт), Tomcat 200 потоков, sched-пул 4.

- **Ingestion:** на батч ≤100 событий — 3 lookup-запроса, 2 Redis-операции на событие, 1 upsert `sessions` на сессию, 1 `batchUpdate` в `events`, затем `scoreInline` и `RewardService.onEventIngested` — для FORM_SUBMIT **синхронный HTTP в ML** внутри запроса трекера. При 5k сессий/сут × ~15 событий ≈ 75k событий/сут → ~27 M строк/год; **~25–40 GB/год на тенанта такого размера**. Транзакции нет: частичный сбой возможен.
- **`/public/l/{slug}`:** 3 запроса, кэша нет; с `host` +1. **`/public/decide`:** `runner_keys` SELECT + **UPDATE `last_seen_at`** + `tenant_domains` + 3 запроса варианта + HTTP в ML (config, `load_arms` 1–4 запроса, `meta_ad_map`, INSERT `decision_log`) → **≈10–12 запросов к БД и 1 HTTP на клик, кэша нигде нет**. Для 10 rps пилота приемлемо; при 100 rps упрётся в Hikari=10 вместе с D-12.
- **`decision_log`:** ~1 KB на решение → ~2–3 GB/год при 5k/сут. **`site_health_checks`:** 10 лендингов = **5.3 M строк/год** без ретеншена.
- **Ночные пересчёты:** NicheBenchmark (D-4, потенциально часы), Diagnostics/NicheFeatureStore (коррелированный `NOT EXISTS` по `events` на каждую сессию за 90 дн), ClusterFit (N+1), FraudL3 (ежечасно 24 ч событий в память). Индекс `events(tenant_id, event_type, event_time)` отсутствует.
- **Retention:** `DELETE FROM events` вместо `DROP PARTITION` — один DELETE на 2–3 M строк с WAL и bloat; плюс падение по FK (D-5).

### 5.6. Итог по области

Схема миграций дисциплинированна: 136 миграций без опасных DELETE, RLS с FORCE и bypass-GUC на всех 130 тенантных таблицах, партиции `events` создаются автоматически, single-writer по `thompson_arms` подтверждён грантами и кодом, SQL-инъекций нет. Основные проблемы в том, что часть защитных пайплайнов **тихо не работает**: quality_score никогда не считается из-за противоречивого окна (D-2), L1 budget guard не получает spend (D-3), все «автопаузы» и kill на гейтах меняют только БД и не доходят до Meta (D-1), burnout/graveyard зависят от таблиц, в которые никто не пишет (D-10), аудит P0-пауз падает на CHECK (D-11). Два риска данных: первый тенант еженедельного отчёта считается под bypass и получает цифры всех тенантов (D-7); retention и GDPR-erasure упадут на FK к `sessions` (D-5). Операционная хрупкость: `RestTemplate` без таймаутов на 4-поточном sched-пуле и Hikari=10 (D-9, D-12), Redis как жёсткая зависимость ingestion (D-8), отсутствие ShedLock (D-18) и ретеншена для высокочастотных логов (D-20). `ddl-auto=validate` фактически выключен профилем `local` (D-16).

---

## 6. ML-сервис (ml-service)

**Охват.** Прочитаны целиком `main.py`, `app/config.py`, `app/security.py`, `services/security_service.py`, `services/db_client.py` (ScopedPool), `routers/decide.py` (795 строк), `services/thompson_service.py` (748), `pair_service.py`, `ad_map_service.py`, `ts_sampling_service.py`, `decide_service.py`, `evi_service.py`, роутеры `evi, reward, health, video, analyze, taxonomy, glm, diagnostics, propensity, media_compliance, circuit_breaker`, сервисы `llm_client, job_store, transcription_service, video_service, subtitle_service, tts_service, creative_dna`, `models/bayesian_glm.py`, `requirements.txt`, `Dockerfile`, `conftest.py`. Фрагментарно остальные роутеры и сервисы. AST-скан всех 48 модулей роутеров (149 маршрутов). Со стороны Java: `MlServiceClient`, `PublicLandingController`, `RewardService`, `RewardSweepScheduler`, `PropensityController`, `ThompsonArmRepository`, V4/V115/V116/V127; фронт `middleware.ts`.

### 6.1. Таблица роутеров

Ключ: все 47 не-health роутеров закрыты одной router-level зависимостью `require_internal_key` (`main.py:38-88`), fail-closed с `hmac.compare_digest` (`security_service.py:20-24`). CORS middleware отсутствует. Лимита размера тела нет, `uvicorn` одним воркером без таймаутов (`Dockerfile:30`).

| Модуль | Префикс | Пул | Блокирующие вызовы в `async def` | Вызывающие из Java / мёртвые маршруты |
|---|---|---|---|---|
| health | `/health` | — | — | без ключа (намеренно), отдаёт `{"status","service"}` |
| decide | `/decide` | tenant (`load_arms`, `log_decision`, `ensure_arms`), **system** (`_load_decision`, `apply_reward`, `sweep`) | CPU-циклы на loop: `power_calculator` (10k×k), `sample_arms` (200×k) | PublicLandingController, middleware напрямую, RewardService, RewardSweepScheduler, ChurnPenaltyService, ExperimentService, Followup*, FormSubmissionController, LeadValueService, NoshowPropensityService, BenchmarkController, LandingSetController. **Мёртвые 18**: `/evi`, `/power/{t}`, `/personalise`, `/refine-cluster`, `/followup/show-rate-posterior`, `/reward/compute-stage`, `/reward/funnel-progress`, `/reward/quality-rate`, `/form/lead-score`, `/form/validate`, `/experiment/ranked-arms`, `/decide/funnel-path` (двойной префикс), `/funnel-paths/metrics`, `/propensity/slot-fill`, `/predict/lead-value/calibrate`, `/compliance/compose-from-approved`, `/persona/score` |
| analyze | `/analyze` | tenant | нет (ffmpeg/Whisper через `to_thread`); **самовызов по HTTP** `/taxonomy/align` с неопределённым именем | MlServiceClient, FormHealthScheduler, NoshowAttributionScheduler, MatchScoreCalibrationScheduler. Мёртвые: `/diagnostics`, `/fraud/*` (3), `/lpv-gap`, `/ope/ips`, `/segment-effects`, `/status/{job}` |
| video | `/video` | — | **да**: sync Whisper `:103`, sync `anthropic.Anthropic` `:166,:242`, `subprocess.run(ffmpeg, timeout=300)` в async `:365`, sync boto3 `:442,:595`, sync TTS | VideoController (все 8) |
| brief | `/brief` | tenant | **да**: sync Anthropic `brief.py:370-402` | BlockedCreativeRecoveryScheduler; мёртв `/generate-data-driven` |
| competitive | `/competitive` | — | **да**: `:92-94` | CompetitiveIntelligenceService |
| voc | `/voc` | — | **да**: `:70,86` | VocController |
| compliance | `/compliance` | — | **да**: `/check-text` → sync `LLMClient().complete` | ComplianceController, ModerationController, ComplianceService; мёртвые `/check/personal-attr`, `/check/approved-phrases` |
| content | `/content` | — | **да**: 3 роута → sync LLM | ContentGenerationController, LandingSetService, RlhaService |
| explain | `/explain` | — | **да**: `:44` | WeeklyReportScheduler |
| taxonomy | `/taxonomy` | **system** (кросс-tenant реестр по дизайну) | **да**: sync OpenAI embeddings в `/align` | TaxonomyController/Scheduler; `/align` — только самовызов из analyze (сломан, ML-2) |
| post_call | `/post-call` | — | **да**: sync Whisper | PostCallController, CallRecordingController; мёртв `/summary` |
| glm | `/glm` | tenant | нет (PyMC в executor) | **все 6 мёртвые** |
| diagnostics, evi | — | — | — | **мёртвые** |
| ai_video, media_compliance, geo_lift, portfolio, signals, cvr_collapse, circuit_breaker, reward, propensity, cluster, attribution | свои | — | нет / sync `def` | живые; мёртвые: `/portfolio/markowitz`, `/signals/anomaly`, `/signals/route`, `/cb/cpa-exceedance`, `/reward/gate-adjustment`, `/propensity/fit`, `/cluster/ips-coverage`, `/attribution/mta` |
| agency, burnout, cpm, entropy, evolve, graveyard, investor_archetype, mmf, mmm, ope, persona, predict, red_flags, regcf, simulate | свои | — | sync `def` | живые; мёртвые: `evolve/{coherence-check,graveyard-check,rank-candidates,rank-with-diversity}`, `mmf/offers/{generate,rank,validate-schema}`, `persona/build` |
| anchors, hierarchy, niche_store, offer_validity, model_quality, quality, saturation | свои | — | — | **целиком мёртвые** (7 модулей) |

### 6.2. Инварианты, подтверждённые кодом

1. **Единственный писатель `thompson_arms` — ML.** `V116:65` `REVOKE INSERT, UPDATE, DELETE ON thompson_arms FROM mai_app`; в Java нет ни `armRepo.save`, ни native UPDATE; `ThompsonArmRepository` — только `find*`.
2. **Целочисленные α/β.** `REWARD_MAP` все дельты 1.0 (`thompson_service.py:49-58`); churn-penalty `floor` + cap 30 %.
3. **Decay к приору, ленивый, относительный.** `alpha = 1 + power(0.99, days)*(alpha-1)` только при `last_decayed_at < now()-24h` (`:233-252`).
4. **Propensity пишется до ответа и атомарно.** `decide.py:216-225` `await log_decision(...)` → `INSERT ... RETURNING id`, затем `return DecideResponse`.
5. **`placement`/`site_source` — контекст, не плечо** (`decide.py:155-160`).
6. **`ensure_arms` идемпотентен:** `INSERT ... NOT EXISTS ... ON CONFLICT DO NOTHING` под `UNIQUE(tenant_id, variant_id, context_key)`.
7. **Идемпотентность reward по (decision_id, reward_type).** V115 PK + `ON CONFLICT DO NOTHING RETURNING 1` в одной транзакции с UPDATE (`:505-530`).
8. **Tenant-scope.** Все SQL через `tenant_pool`/`system_pool` (grep `get_pool()` вне `db_client.py` — 0); `system_pool` только в `taxonomy` и трёх функциях Thompson, все с явным `tenant_id` в WHERE. Compose подключает `mai_ml`.
9. **SQL-инъекций нет:** единственная f-строка `thompson_service.py:669` подставляет `field из {"alpha","beta"}`.
10. **Стабы помечены явно** (`_STUB_*`, `provider:"stub"`, `stub:True`, провайдер `MOCK`).
11. **Pickle-RCE закрыт:** `/propensity/score` принимает `model_b64` только с HMAC от `/fit`.
12. **ffmpeg без shell**, argv-список; `drawtext` и ASS экранируются; имя файла субтитров = серверный `job_id`.
13. **Model id из `config.py:8-10`** — валидные текущие идентификаторы.

### 6.3. Находки

| ID | Серьёзность | Где | Что не так | Доказательство | Как исправить |
|---|---|---|---|---|---|
| ML-1 | **P0** | `video.py:103,166,242,365,442,595`; `brief.py:370-402`; `competitive.py:92-94`; `voc.py:70,86`; `compliance.py:37`+`compliance_service.py:42`; `content.py:55,73,86`; `explain.py:44`; `taxonomy_service.py:35`; `transcription_service.py:49`; `tts_service.py:148,161` | Синхронные вызовы Claude/OpenAI/Whisper/ffmpeg/boto3 внутри `async def` — на event loop. `uvicorn` один воркер. Любой LLM-вызов (2–15 с) или рендер (`subprocess.run(..., timeout=300)` в `async def _run_render`, до 5 минут) замораживает **все** запросы, включая `/decide/landing` на пути клик → лендинг | `video.py:326` `async def _run_render` → `:365` `subprocess.run(ffmpeg_cmd, …, timeout=300)`; `video.py:137` `async def tag_scenes` → `:166` `client.messages.create(` | `AsyncAnthropic`/`AsyncOpenAI` (как уже в `signals.py:141`) либо `asyncio.to_thread` (как в `analyze.py:208-215`); ffmpeg → `asyncio.create_subprocess_exec`; рендер/транскрипцию в очередь вне веб-процесса; `uvicorn --workers N` |
| ML-2 | **P1** | `analyze.py:167` | `NameError`: `_INTERNAL_KEY` нигде не определён в модуле. Ошибка проглатывается `except Exception` → таксономическое выравнивание атомов **никогда не выполняется**, в логах лишь warning «non-critical failure» | AST: `_INTERNAL_KEY defined: False`; `:177` `except Exception as exc: logger.warning(...)` | Убрать самовызов по HTTP: вызывать логику `/taxonomy/align` напрямую; тест, что align вызывается |
| ML-3 | **P1** | `thompson_service.py:49-58, 495-530`; V115; `PublicLandingController.java:35,166-188`; `RewardService.java:131-152` | Одно решение может дать до **7 успехов** (CTA_CLICK + FORM_START + FORM_SUBMIT + CALL_ANSWERED + MEETING_ATTENDED + CONVERSION + PURCHASE), каждый `alpha+1, total_samples+1`. Противоречит докстрингу «ONE binary event per arm… one Bernoulli observation». Сессии с длинной воронкой весят в 3–7 раз больше | `BROWSER_REWARD_TYPES = {CTA_CLICK, FORM_START, FORM_SUBMIT}`; идемпотентность только на пару `(decision, type)` | Решить семантику явно: либо partial unique index `(decision_id) WHERE field_updated='alpha'`, либо многоуровневые награды с весами и исправленный докстринг. Тест на двойной позитив |
| ML-4 | **P1** | `video.py:91,340,409`; `media_compliance.py:40`; `transcription_service.py:23-27`; `llm_client.py:120-122` | Скачивание `storage_url`/`footage_url`/`audio_url`/`track_url`/`recording_url`/`thumbnail_url` без `assert_public_http_url` (guard применён только в `analyze.py:350,360` и `signals.py:164`). ML-хост в docker-сети имеет доступ к `postgres`, `redis` | `video.py:339-341` `await client.get(req.footage_url)` без guard | Применить `assert_public_http_url` ко всем загрузкам либо allowlist доменов хранилища; лимит размера в `video.py` |
| ML-5 | **P1** | `thompson_service.py:478-497`; `decide.py:285-297`; `PublicLandingController.java:166-188`; `middleware.ts:198-203` | `/decide/reward` принимает только `{decision_id, reward_type}`, тенант берётся из `decision_log` через `system_pool`; нет привязки к `session_id`. Публичный `/public/reward` пересылает любой `decision_id` из браузера без проверки сессии и **без rate limit**. `decision_id` лежит в cookie `_decision` с `httpOnly:false`. Скрипт: 600 decide/мин × 3 типа наград → накачивание α выбранных плеч | `apply_reward(decision_id, reward_type, tenant_configured)` без tenant/session | В ML проверять `decision_log.tenant_id = $tenant AND session_id = $session`; в Spring rate-limit `/public/reward`; долгосрочно — награды только из серверно наблюдаемых `events` |
| ML-6 | **P1** | `main.py:25-29` (пустой lifespan), `model_updater.py:6-7` («GlmScheduler — TODO»), `glm.py:128-135`, `propensity.py:90`; Java grep `glm` — 0, `/propensity/fit` — 0 | `POST /glm/fit` и `POST /propensity/fit` никто не вызывает. Следствия: `glm_posteriors` пуст → `_apply_glm_priors` (roadmap 9-10) всегда no-op, новые плечи стартуют с Beta(1,1); `/glm/predict` всегда 404; `propensity_models` пуст → `/propensity/score` всегда rule-based | список «не вызывается из Java» | Spring-scheduler (ночной `/glm/fit`, еженедельный `/propensity/fit`) либо честно пометить в документах как не включённое |
| ML-7 | **P1** | `decide.py:179-184`, `thompson_service.py:113-143`; V116:65; `VariantService.java:68,379` | Плечи создаются **только** когда у тенанта нет вообще ни одного плеча. Вариант, активированный после первого решения, никогда не получает строку в `thompson_arms` (Spring не может INSERT — грант отозван) → `load_arms` его не вернёт, он никогда не будет показан | `decide.py:179` `if not arms: ... await ensure_arms(...)` — единственный вызов | Вызывать `ensure_arms` на каждом промахе Redis-кэша либо внутренний маршрут `POST /decide/arms/ensure`, который Spring дёргает при активации варианта. Тест: активировать вариант B после решения |
| ML-8 | P2 | `decide.py:627` | Маршрут `/decide/decide/funnel-path` — опечатка при `prefix="/decide"`; Java не вызывает | AST | Переименовать или удалить |
| ML-9 | P2 | `decide.py:388-421` | `n_simulations` из query без верхней границы; 10 000×k скалярных `np.random.beta` на event loop | `n_simulations: int = 10_000` без `le` | `Query(le=50_000)`, векторизовать как `pair_service.win_probabilities`; то же для `sample_arms` (200×k скаляров на каждое решение) |
| ML-10 | P2 | `thompson_service.py:227-230, 359-372` | Redis — жёсткая зависимость пути `/decide/landing`. При недоступности Redis решение не выдаётся вообще, хотя строка в `decision_log` уже записана | `await redis.get(cache_key)` без try/except; `setex` после INSERT | Кэш в try/except с деградацией на БД; `setex` best-effort |
| ML-11 | P2 | `main.py`, `Dockerfile:30`, `glm.py:29-33`, `propensity.py:47-52`, `video.py:282-291` | Нет лимита тела и таймаута keep-alive; `draws/tune/chains` для PyMC, списки `sessions`, `plan`, `segments` неограничены. Один воркер без `--limit-concurrency` | `FitRequest.draws: int = 1000` без `le` | `Field(le=…)`; middleware на `Content-Length`; `--workers`, `--timeout-keep-alive` |
| ML-12 | P2 | `config.py:12`, `docker-compose.yml:74,95`, `middleware.ts:176-183` | Дефолт внутреннего ключа `local-internal-key` в коде и в compose → при незаданной переменной прод работает на публично известном ключе. Фронт при `ML_SERVICE_URL` зовёт ML **напрямую** с `INTERNAL_API_KEY` — расходится с утверждением «единственный вызывающий — Spring» | `internal_api_key: str = "local-internal-key"` | В проде ключ обязателен; убрать прямую ветку из middleware, оставить `/public/decide` |
| ML-13 | P2 | `job_store.py:17-25`, `video.py:314-323,326-404`, `analyze.py:152-162`, `ai_video.py:178-186` | Статусы задач `job:{uuid}` без tenant; `BackgroundTasks` в процессе — при рестарте рендер теряется, статус `running` до TTL 1 ч; результат рендера и `.ass` остаются в `video_output_dir` навсегда | `job_store.set_job(job_id, {...})` без tenant | Ключ `job:{tenant}:{id}`; очередь с воркером и heartbeat; retention |
| ML-14 | P2 | `llm_client.py:12-20,27-32`; `video.py:149,215`; `brief.py:370`; `competitive.py:92`; `compliance.py:31`, `content.py:36-49`, `explain.py:26` | Клиенты создаются на каждый вызов без `timeout`/`max_retries` (дефолт SDK: 10 мин, 2 ретрая). Модель захардкожена (`"claude-sonnet-5-5"` в `video.py:167,243`, `brief.py:402`, `competitive.py:94`) в обход `settings.llm_model_*`; в compliance/content/explain модель приходит **в теле запроса** | см. строки | Фабрика `get_async_client()` с таймаутами; модели только из settings; `model` убрать из request-схем |
| ML-15 | P2 | `video.py:151-163,223-239`; `llm_client.py:70-85,105-112`; `compliance_service.py:33-35`; `content_service.py:91-108`; `creative_dna.py:73-95` | Пользовательский контент (транскрипт, текст лендинга, `avoid_notes`) вставляется в промпт f-строкой без разделителей — prompt-injection. Выход LLM не валидируется схемой: `creative_dna` сохраняет **любые** `type/value` в `creative_atoms`; `compliance` доверяет `data.get("result","PASS")` — инъекция может вернуть PASS; `content_service` при непарсибельном ответе кладёт сырой текст во все слоты | `creative_dna.py:75-82` без allowlist; `compliance_service.py:42-44` | Контент в `<user_content>` + system prompt; pydantic-модели для ответов; для compliance дефолт при ошибке парсинга — не PASS |
| ML-16 | P2 | `models/bayesian_glm.py:207-217, 296-300` | `pm.sample` без `random_seed`/`cores`; при R-hat > 1.1 только warning, постериоры сохраняются как `DONE`. `mlflow_service` не имеет вызывающих | `log.warning("… R-hat …")` и далее сохранение | `random_seed` из конфигурации; при `r_hat_max > 1.1` статус `DEGRADED` без перезаписи |
| ML-17 | P2 | `cpm_service.py:40`, `geo_lift_service.py:12`, `ai_video_service.py:11` | Модульные `_RNG = default_rng(seed=42)` делятся всеми запросами из threadpool — `numpy.Generator` не потокобезопасен; «детерминизм» мнимый | — | `default_rng(seed)` на вызов |
| ML-18 | P2 | `tests/*` | Для I/O-ядра (`apply_reward`, `sweep_no_response`, `ensure_arms`, `load_arms`, `log_decision`, `decide_landing`) **ни одного теста**; нет `TestClient`-тестов ни на один роутер (поэтому ML-2 и ML-8 не пойманы); нет тестов RLS под `mai_ml`; стабы через `sys.modules` порядок-зависимы (`test_ts_sampling.py:41` skip) | `TestClient|AsyncClient` — 0 файлов | `pytest-asyncio` + testcontainers с V116/V127: decide→reward→sweep→reverse, двойной reward, RLS-отказ |
| ML-19 | P3 | `thompson_service.py:236-243` | Decay пишет `last_decayed_at = now()` после `floor(days)` — остаток часов теряется | — | `last_decayed_at = last_decayed_at + d.days * INTERVAL '1 day'` |
| ML-20 | P3 | `thompson_service.py:514-524` | Обратный sweep вычитает ровно 1 из β, хотя после decay вклад уже < 1 | — | Допустимая погрешность либо хранить вклад |
| ML-21 | P3 | `decide.py:281` | `win_probabilities(pairs, draws=200)` — разрешение 0.5 % | — | `draws=2000` |
| ML-22 | P3 | `video.py:53-55`, `geo_lift.py:19-21`, `media_compliance.py:23-25` | Дублирующие локальные проверки ключа `!=` без `compare_digest`, fail-open при пустом ключе (перекрыты router-level, но ловушка при рефакторинге) | — | Удалить локальные проверки |
| ML-23 | P3 | `Dockerfile`, `requirements.txt` | Нет `.dockerignore` (`COPY . .` затянет `tests/`, локальный `.env`); `gcc` в образе; нет `USER`; `python-jose`, `sqlalchemy`, `alembic` не используются; `anthropic==0.40.0` старый | — | Multi-stage, `.dockerignore`, non-root, убрать лишнее |
| ML-24 | P3 | `decide.py:135,159-184` | `channel` из тела не валидируется, при пустых плечах создаётся набор для `channel:<любая строка>` | — | `Literal["META","GOOGLE"]` |
| ML-25 | P3 | `thompson_service.py:252-257` | Нечисловое `min_margin` в `tenants.settings` → cast-ошибка → 500 на каждом `/decide/landing` тенанта | — | safe-cast или валидация в Spring |
| ML-26 | P3 | `thompson_service.py:34` | Неиспользуемый импорт `get_pool` | — | Удалить |

### 6.4. Мёртвые и спящие модули

Полностью мёртвые роутеры (7): `anchors`, `hierarchy`, `niche_store`, `offer_validity`, `model_quality`, `quality`, `saturation`; плюс `glm` (6 маршрутов), `diagnostics`, `evi`. Мёртвые сервисы: `mlflow_service`, `hierarchy_prior_service`, `niche_feature_store_service`, `niche_benchmark_service`, `model_quality_service`, `quality_service`, `saturation_service`, `anchors_service`, `offer_validity_service`, `evi_service`, `portfolio_service.markowitz`, `experiment_service.thompson_best_arm`, `funnel_path_service`, `form_qualification_service`, `reward_service.compute_stage_reward/…`, `cluster_refinement_service`, `lead_value_service.calibrate`, `noshow_service.slot-fill`. Фактически мёртвы из-за отсутствия триггера: `bayesian_glm` + `model_updater` + `glm_prior_service` (ML-6), `propensity_service.fit_propensity_model` (ML-6), `taxonomy /align` (ML-2). **Итого 85 из 149 маршрутов не имеют вызывающего.** (`scripts/check-ml-routes.py` проверяет обратное направление — что у каждого Java-вызова есть маршрут, — поэтому «148/0» и «85 мёртвых» не противоречат друг другу.)

### 6.5. Тесты

89 файлов, 1088 тест-функций, все **без БД, без Redis, без FastAPI** (I/O-модули подменяются `MagicMock` через `sys.modules`). Хорошо покрыто: `REWARD_MAP`/decay/churn/holdout, top-two sampler, EVI, pairs, ad-map, порядок GUC в ScopedPool (на FakePool), security (ключ/HMAC/SSRF), ffmpeg-команда и субтитры, математика прошлого аудита. Не покрыто: путь `/public/decide → /decide/landing → log_decision → /decide/reward → sweep → reverse` с реальным asyncpg, идемпотентность reward на уровне БД, `ensure_arms` при гонке, RLS под `mai_ml`, любой роутер end-to-end, `_align_atoms` (ML-2), валидация атомов из LLM, `_run_render`.

### 6.6. Итог по области

Контур безопасности в порядке: один fail-closed внутренний ключ на всех 47 роутерах, нет SQL-инъекций, все запросы через `ScopedPool` ролью `mai_ml` под RLS, Spring действительно не пишет `thompson_arms`, propensity логируется до ответа, α/β целочисленны, decay относительный. Главная проблема — **асинхронная дисциплина**: девять роутеров делают синхронные вызовы LLM/Whisper/ffmpeg/boto3 внутри `async def` на одном uvicorn-воркере, а рендер держит `subprocess.run` до 300 с прямо в event loop; на проде это замораживает `/decide/landing` на время любого LLM-запроса. Второй блок — ядро Thompson: одно решение может накапливать до семи α-инкрементов вопреки задокументированной модели, `/decide/reward` не привязан к tenant/session и доступен из браузера без rate-limit, новые варианты после первого решения никогда не получают плеча. Третий — спящие функции: `/glm/fit` и `/propensity/fit` никто не вызывает, поэтому GLM-приоры и обученная propensity-модель не работают, а таксономическое выравнивание тихо падает на неопределённом имени. Тестовая база широкая, но ни один тест не трогает БД и роутеры.

---

## 7. Фронтенд и контракт фронт ↔ backend

**Охват.** Прочитаны целиком `middleware.ts`, `lib/hosts.ts`, `lib/api.ts`, `lib/tracker.ts`, `lib/consent.ts`, `lib/recaptcha.ts`, оба layout, все 20 страниц `(admin)/*`, `login`, `register`, `privacy`, `l/[slug]`, `l/preview/[id]`, `LandingRenderer.tsx`, 14 блоков, `CookieBanner`, `AuthGuard`, `Sidebar`, `DomainsCard`, `next.config.ts`, `Dockerfile`, `docker-compose.runner.yml`, `docker-compose.yml`, `.env.runner.example`, `package.json`, `tsconfig.json`. На бэкенде: полный дамп маппингов всех контроллеров (скрипт) плюс код `PublicLandingController`, `EventController`, `DomainController`, `TenantController`, `FileController`, `LandingSetController`, `AuthController`/`AuthService`, `SecurityConfig`, DTO (`VariantResponse`, `VariantRequest`, `CreativeResponse`, `EventDto`, `BriefResponse`, `PixelResponse`, `CampaignResponse`, `AuthResponse`, `MetaCredentialsRequest`, `RegisterRequest`, `ComplianceCheck`), ключи ответов `MetaAdMap`, `Autonomy`, `MetaWrite`, `Rlha`, `Ghl`, `Month1`, `OpenItems`, `Reward`, `Onboarding`, `Funnel`, `QualityScore`; в ML `decide.py`. Проверены `node_modules/next` (версия, механизм `x-middleware-set-cookie`) и `react-dom` (обработка `javascript:`-URL).

### 7.1. Страницы ↔ endpoint'ы

| Страница | Вызовы (метод + путь) | Есть в контроллере | Поля ответа/запроса | Loading / error / empty |
|---|---|---|---|---|
| `/dashboard` | GET `/campaigns` | да | camelCase да | **ошибка проглатывается → «No campaigns yet»** (`dashboard/page.tsx:17`) |
| `/creatives` | GET `/landings` | да | **`v.placement` нет в `VariantResponse`** → всегда «—» | ошибка → пустой список |
| `/creatives/[id]` | GET `/landings/{id}`; POST `/landings/{id}/approve` | да | **`placement`, `atoms` нет в DTO** → «Unknown placement», блок атомов не рендерится | текст ошибки = «Unprocessable Entity» (F-5) |
| `/landings` | GET `/landings/sets`; GET `/landings?set_id=`; POST `/landings/generate-set`; PUT `/landings/{id}?edit_reason=`; POST `/landings/{id}/submit`; POST `/landings/{id}/reject` | да все | да | да |
| `/meta-ads` | GET `/creatives`, `/meta/ad-map`, `/meta/linkage-health`, `/campaigns`, `/landings?status=ACTIVE`, `/domains`, `/ad-sets?campaignId=`; POST `/creatives/{id}/activate|pause`; POST `/meta/ad-map/{id}/link`; POST `/meta/ad-map/{id}/acknowledge-drift`; GET `/creatives/{id}/publish-preview`; POST `/creatives/{id}/publish` | да все | **`linkage-health`: фронт читает `activeAds`/`missingTags`, бэк отдаёт `active_ads`/`missing_tags_count`** | все 6 загрузок через `safe()` → любая ошибка невидима |
| `/ab-results` | GET `/landings/arms`; GET `/landings/pairs?days=` | да | `variant_name` = UUID (`VariantController:99`) | да |
| `/quality` | GET `/quality-score?by&days` | да | да | да |
| `/briefs` | GET `/briefs`; PUT `/briefs/{id}/approve` | да | `clusterLabel` нет в `BriefResponse` → «—» | approve → `alert()` |
| `/briefs/[id]` | GET `/briefs/{id}`; PUT `/briefs/{id}/approve` | да | **`is_draft`, `goal`, `brief_type`, `cluster_label`, `target_audience`, `voc_section` — ни одного такого поля нет** (`BriefResponse`: `status`, `title`, `briefType`, `targetAudience`, `productInfo`, `notes`) | статус всегда «APPROVED», кнопка Approve не появляется |
| `/briefs/generate` | POST `/briefs/generate-data-driven` | да | да | да |
| `/funnel` | GET `/funnel/me`; `/reward/ladder`; `/onboarding/checklist` | да | да | двойной padding |
| `/month1` | GET `/month1/*` ×3, `/open-items` | да | да | ошибки проглатываются; **заголовки `text-white` на светлом фоне** |
| `/pixels` | GET/POST `/pixels`; PUT/DELETE `/pixels/{id}`; PUT `/pixels/{id}/default` | да | да | да |
| `/compliance` | GET `/compliance/{entityId}`; PUT `/compliance/{id}/override`; POST `/compliance/check` | да | да | да |
| `/ghl` | GET/POST/DELETE `/ghl/subaccounts`; GET `/ghl/integrity` | да | да | да |
| `/autonomy` | GET/PUT `/tenants/me/autonomy`; GET `/ope`; POST `/ope/evaluate` | да | да | не-админ получит «Forbidden» без пояснения |
| `/meta-write` | GET `/tenants/me/meta-write`; PUT `…/stage`, `…/policy`; POST `…/overrides`; GET `…/decisions` | да | да | да |
| `/rlha` | GET `/rlha/metrics`, `/rlha/corrections`, `/rlha/render-feedback`; PATCH/POST `/rlha/corrections` | да | да | `toggle`/`setReason` без try/catch |
| `/settings` | GET/PATCH `/tenants/me/settings`; GET/PUT `/tenants/me/meta-credentials`; DomainsCard: GET/POST `/domains`, POST `/domains/{id}/verify`, DELETE, POST `/domains/{id}/runner-key` | да все | `creative_download_mode` да; `meta_page_id` через `@JsonAlias` да; домены snake_case да | `alert("Save failed")`; `POST /domains/{id}/disable` в UI нет |
| `/login`, `/register` | POST `/auth/login`, `/auth/register` | да | да | да |
| `/l/[slug]` (SSR) | GET `/public/l/{slug}?host=` | да | `blocks` `@JsonRawValue` → массив да | 404 при неизвестном slug/host |
| `/l/preview/[id]` | GET `/landings/{id}` (JWT) | да | да | да |
| `LandingRenderer`/блоки | POST `/events`; POST `/public/form/submit`; POST `/public/reward` | да | `EventDto` camelCase да, batch ≤100 да | **форма CTA не показывает ни успех, ни ошибку, не очищается** |
| `middleware` | GET `/public/domain/{host}`; GET `/public/l/{slug}`; POST `/public/decide`; ML POST `/decide/landing` | да | да | fallback-вариант при любой ошибке |

Расхождений путей и методов между 25 страницами и контроллерами **нет** — все маршруты существуют. «Тихие» баги живут в именах полей.

### 7.2. Инварианты, подтверждённые кодом

- `NEXT_PUBLIC_API_URL` нормализуется к `…/api/v1` ровно один раз (`api.ts:14-19`); `context-path: /api/v1` на бэке.
- `_sid` (`HttpOnly`, 30 дн.), выставленный middleware, виден в `cookies()` SSR-страницы в том же запросе через `x-middleware-set-cookie` (Next 15.0.3) → `sessionId` в `Tracker.init` совпадает с ушедшим в `/decide`.
- `RUNNER_KEY`, `INTERNAL_API_KEY`, `API_INTERNAL_URL` читаются только в edge/SSR, без `NEXT_PUBLIC_` → в клиентский бандл не попадают.
- `mai_c`: UUID → `creative_id`, иначе → `context.meta_ad_id`; `mai_p`/`mai_s` только контекст; значения идут в JSON-тело, инъекции в заголовки нет.
- Tenant по URL подменить нельзя: `?host=` формируется из `Host`/`X-Forwarded-Host`, а не из query.
- `/l/preview/*` на тенантском домене и раннере → 404; на платформе данные только через JWT-эндпоинт; черновики через SSR не утекают.
- Блоки: нет `dangerouslySetInnerHTML`; `iframe src` и ссылки DataRoom/Materials фильтруются `safeHttpUrl`; `postMessage` календаря проверяет `origin`; `javascript:` в `cta_url` React 19 нейтрализует.
- Edge middleware не использует Node-only API; `tsconfig` `strict: true`; `output: "standalone"`.
- Трекер: батч 5 с, ≤100 событий, `keepalive`, flush на `beforeunload`/`visibilitychange`; поведенческие события требуют `full`, конверсии — всегда.
- CORS на бэке = настроенные origin + все VERIFIED домены тенантов, кэш 60 с.

### 7.3. Находки

| ID | Серьёзность | Где | Что не так | Доказательство | Как исправить |
|---|---|---|---|---|---|
| F-1 | **P1** | `frontend/Dockerfile:26` | Сборка образа падает: `COPY --from=builder /app/public ./public`, а каталога `frontend/public` нет (ни в ФС, ни в git). Ломает и платформенный образ, и `docker-compose.runner.yml` (режим C) | `test -d public` → «NO public dir» | Создать `frontend/public/.gitkeep` или убрать строку COPY |
| F-2 | **P1** | `package.json:13-14` | Next 15.0.3 уязвим к CVE-2025-29927 (обход middleware заголовком `x-middleware-subrequest`, исправлено в 15.2.3). Вся host-логика (404 админки на раннере, запрет `/l/preview`) и назначение варианта обходятся одним заголовком. Плюс `react 19.0.0-rc.1` с `@types/react ^18` | `"next": "15.0.3"` | Обновить до 15.2.3+ (лучше 15.4.7+), React до 19.x release |
| F-3 | **P1** | `api.ts:142-160`; `AuthService.java:108-113` | Гонка refresh: при истёкшем access-токене страница с параллельными вызовами (meta-ads — 6, month1 — 4) делает N одновременных `POST /auth/refresh` с одним refresh-токеном; бэк ротирует, все кроме первого получают 401 → `clearAuth()` + редирект на `/login`. Пользователь «выкидывается» через срок жизни access-токена | `AuthService:109 "UPDATE refresh_tokens SET revoked = TRUE"` | Единый in-flight promise refresh в `apiFetch`; повтор запросов после него |
| F-4 | **P1** | `briefs/[id]/page.tsx:26,49,61,72,79,86` | Страница читает `is_draft`, `goal`, `brief_type`, `cluster_label`, `target_audience`, `voc_section`; `BriefResponse` отдаёт `status`, `title`, `briefType`, `targetAudience`, `productInfo`, `notes`. Статус всегда «APPROVED», кнопка «Approve Brief» никогда не рендерится | `dto/brief/BriefResponse.java` | Использовать реальные поля |
| F-5 | P2 | `api.ts:26-29`; platform-api без `@ControllerAdvice` | `ApiError.message` берёт поле `error`; у Spring по умолчанию это reason-phrase («Unprocessable Entity»), а reason `ResponseStatusException` не выдаётся. Маркетолог видит «Conflict» вместо «Variant cannot be approved: no passing compliance check» | `grep RestControllerAdvice` → пусто | `@RestControllerAdvice` с единым `{error, detail}`/ProblemDetail; на фронте читать `detail` |
| F-6 | P2 | `consent.ts:237-243`; `CookieBanner.tsx:29-31`; `middleware.ts:149-150` | Cookie `_consent` ставится только кастомным баннером. При Cookiebot cookie не ставится никогда → middleware всегда считает «нет согласия» и шлёт `session_id: null` в `/decide` — персистентная идентификация в Thompson не работает в проде с CMP | `grep setConsentLevel` — единственный вызов в `CookieBanner` | В обработчиках `CookiebotOnAccept|Decline` писать `_consent` |
| F-7 | P2 | `middleware.ts:47-59,88,107-108` | Недоступность API на тенантском домене/раннере → `resolveDomain` кладёт `null` в кэш на 60 с → все `/l/*` отдают 404. Документ `landing-hosting.md` (режим C) обещает «покажет закэшированную версию» — по коду это так только на платформенном хосте | `catch { }` → `domainCache.set(host, {info:null})` | Не кэшировать сетевую ошибку; использовать последнее успешное значение |
| F-8 | P2 | `middleware.ts:52,156,173,176`; `l/[slug]/page.tsx:25` | Ни один `fetch` в edge/SSR не имеет таймаута. Зависший API = зависший лендинг | нет `AbortSignal.timeout` | `AbortSignal.timeout(1500)` для decide/domain, 3–5 с для variant |
| F-9 | P2 | `tracker.ts:137-151` | Батч, не доставленный (сеть/5xx), теряется: `splice(0)` до fetch, retry нет, `sendBeacon` нет. В режимах B/C события идут cross-origin с preflight; `keepalive` с preflight на `beforeunload` в ряде браузеров отбрасывается. Очередь не ограничена | код flush | Возвращать батч в очередь при ошибке, ограничить очередь, на unload `sendBeacon` |
| F-10 | P2 | `meta-ads/page.tsx:25-28,151` vs `MetaAdMapController:51-55` | Баннер здоровья: `activeAds`/`missingTags` vs `active_ads`/`missing_tags_count`. «active ads —», «missing url_tags 0» — ложная картина | см. таблицу | Привести тип `Health` к snake_case |
| F-11 | P2 | `docker-compose.yml` (frontend), `hosts.ts:14`, `middleware.ts:81-97` | `PLATFORM_HOSTS` не задан → дефолт `localhost:3000`. На любом другом хосте весь хост считается тенантским: админка и `/login` → 404 | compose без `PLATFORM_HOSTS` | Добавить в compose/README; предупреждение при старте |
| F-12 | P2 | `login/page.tsx:11,38` | `next` проверяется только `startsWith("/")`; `?next=//evil.com` проходит → open redirect после логина | код | `/^\/(?!\/)/` и проверка origin |
| F-13 | P2 | `meta-ads/page.tsx:198-203`; `FileController:156-159` | «download» — обычный `<a href>` на `/files/...`; при `PREVIEW_ONLY` бэк отвечает 403 с `X-Error`, браузер открывает пустую страницу, текст не показывается | код | Скрывать ссылку при `PREVIEW_ONLY` либо качать через `fetch` |
| F-14 | P2 | `api.ts:45-52,131-137`; `next.config.ts`; `middleware.ts:117-218` | JWT + refresh в `localStorage`: любой XSS = кража токена на 30 дней. Нет `headers()` в `next.config.ts` (CSP, HSTS, X-Frame-Options), cookie без `secure`, `_variant`/`_decision` не подписаны | код | CSP + `frame-ancestors`, HSTS, `secure`; refresh в `HttpOnly` cookie через BFF-роут |
| F-15 | P2 | `VariantService:236-237`; `V3:15` | Slug ищется глобально при уникальности `(tenant_id, slug)` → два тенанта с одинаковым ACTIVE slug → чужой лендинг или 500 (= S-6) | код | Глобальная уникальность slug для ACTIVE |
| F-16 | P2 | `middleware.ts:131,194-219`; `PublicLandingController:128` | `_creative` ставится только при новом решении. Если посетитель в течение 1 ч пришёл по другому объявлению (`mai_c` другой), решение не пересчитывается и `_creative` остаётся старым → события приписываются не тому креативу. На раннере при fallback `_creative` не ставится вообще | код | При `mai_c` в URL сравнивать с cookie и обновлять; в fallback прокидывать `creative_id` |
| F-17 | P2 | `LandingRenderer.tsx:92-140`; `CTABlock.tsx:28-36`; `recaptcha.ts` | Лид-форма: ответ `/public/form/submit` не проверяется, нет сообщения «отправлено/ошибка», поля не очищаются (повторный клик = дубль), нет honeypot, `getRecaptchaToken` нигде не вызывается — bot-score в формы не попадает | `grep getRecaptchaToken` — только определение | Состояние `sending/done/error`, honeypot, reCAPTCHA-токен в submit |
| F-18 | P2 | `hosts.ts:23-27` | `X-Forwarded-Host` берётся первым без проверки доверенного прокси | код | Учитывать только при `TRUST_PROXY=1` |
| F-19 | P2 | `dashboard:17`, `meta-ads:79-87`, `month1:8-13`, `briefs:14`, `creatives:17`, `settings:27,35`, `ab-results:22` | Ошибки загрузки превращаются в «пустое» состояние. При падении API маркетолог видит ложное «данных нет» | код | Состояние ошибки с текстом и Retry |
| F-20 | P3 | `month1/page.tsx:42,50,77,…` | Тёмная тема внутри светлого layout — заголовки невидимы | код | Привести к палитре |
| F-21 | P3 | `creatives/page.tsx:79`, `creatives/[id]:65,81`, `briefs/page.tsx:85` | Поля `placement`, `atoms`, `clusterLabel` не существуют в DTO; `/creatives` показывает landing-варианты, а не `creatives` | DTO | Перевести на реальные поля |
| F-22 | P3 | `VariantController:99` + `ab-results:64` | `variant_name` = UUID → карточки A/B без имён | код | Джойнить имя варианта |
| F-23 | P3 | `briefs/page.tsx:27`, `settings/page.tsx:67`, `rlha/page.tsx:37-44` | `alert()` для ошибок; `toggle`/`setReason` без обработки ошибок | код | Inline-сообщения |
| F-24 | P3 | `HeroBlock.tsx:45`, `PricingBlock.tsx:73` | `cta_url` из LLM/БД не фильтруется (в отличие от DataRoom/Materials) | код | `safeHttpUrl` |
| F-25 | P3 | `middleware.ts:40`; `DomainsCard`; `funnel:63`; `dashboard:42,77` | `/api/health` в allow-list без роута; `disable` домена без UI; деньги без форматирования; даты UTC-срезом | код | Косметика |
| F-26 | P3 | `tracker.ts:23-37,160-165` | `DATAROOM_OPEN`, `MATERIAL_VIEW`, `CALENDAR_OPEN`, `SLOT_SELECTED`, `PAGE_VIEW` отправляются при `analytics_only`, хотя поведенческие | код | Решить осознанно |
| F-27 | P3 | `docker-compose.runner.yml:30-33`; `Dockerfile:10-13` | `NEXT_PUBLIC_API_URL` вшивается в билд → один образ на один URL API (приемлемо). Открытый порт раннера: любой может гонять `/l/{slug}` и исчерпать лимит 600 decide/мин на ключ → все посетители получат fallback-вариант | код | Rate-limit на раннере или по IP на API |

### 7.4. Режимы хостинга A/B/C по коду

**A — платформенный хост.** Работает: middleware → `/public/l/{slug}` → ML `/decide/landing` → cookies → SSR с ISR 60 с → трекер на тот же origin. Риски: slug глобальный при уникальности `(tenant_id, slug)` (F-15), нет таймаутов (F-8), `PLATFORM_HOSTS` обязателен (F-11).

**B — домен клиента по CNAME.** Работает: `resolveDomain(host)` требует VERIFIED → `/l/{slug}?host=` скоупит slug к тенанту; CORS автоматически включает домен; `DomainsCard` показывает CNAME-target, TXT, Verify, Delete (нет Disable). Риски: негативный кэш 60 с → 404 (F-7); cross-origin события (F-9); `X-Forwarded-Host` без доверия к прокси (F-18); обход middleware через CVE (F-2).

**C — раннер на сервере клиента.** Работает: `LANDING_RUNNER=1` → только `/l/*`, `/_next/*`; `RUNNER_KEY` только в серверном env; decide через `POST /public/decide` с проверкой владения slug. Не работает: **образ не собирается из-за отсутствующего `public` (F-1)**; обещанная «последняя закэшированная версия при недоступности API» — по коду 404 (F-7); при fallback `_creative` не ставится (F-16).

### 7.5. Итог по области

Контракт фронт↔бэк по путям и методам чистый — все 60+ вызовов 25 страниц находят свой контроллер. «Тихие» баги живут в именах полей: страница брифа сломана полностью (F-4), баннер здоровья Meta показывает прочерки (F-10), несколько страниц обращаются к несуществующим полям (F-21). Самые дорогие проблемы инфраструктурные: `Dockerfile` не соберётся без каталога `public` (F-1, ломает режим C), Next 15.0.3 с известным обходом middleware (F-2), гонка refresh-токенов, разлогинивающая пользователя на любой «тяжёлой» странице после истечения access-токена (F-3). Отсутствие единого обработчика ошибок на бэке превращает осмысленные отказы в «Unprocessable Entity» на экране (F-5). Лендинг и трекер архитектурно корректны, но Cookiebot-путь никогда не ставит `_consent` (F-6), батчи теряются (F-9), недоступность API на доменах клиентов даёт 404 вместо кэша (F-7).

---

## 8. Соответствие документов коду, инфраструктура и репозиторий

**Охват.** `conformance-review-2026-10-20.md` (17 пунктов §2, 19 пунктов §3, 13 строк §4, 8 пунктов §7 — статус по коду с `файл:строка`); `roadmap.md` (все пункты 10-1…10-5 и 11-1…11-5, 44 отмеченных пункта Phase 8/9, Bug List); `architecture.md` v1.7 (§5.1 — сгенерирован фактический список 340 Java-маршрутов из 89 контроллеров; §5.2 — 148 ML-путей из 48 роутеров с обратной проверкой «есть ли Java-вызов»; §9 — 56 scheduler-классов, 60 `@Scheduled`; §4 vs V127–V136; §17; §18); `platform-capabilities.md` §15–16 (пересчёт метрик, поиск стабов); `docker-compose*.yml`, три Dockerfile, `application*.yml`, `config.py`, `.env*.example`, `.gitignore`, `scripts/*`, CI, логирование, health, алерты, бэкапы, README, секреты в git (включая историю `git log -S`).

### 8.1. Статус ревью 2026-10-20

**§2 — 17 расхождений:** 7 закрыто кодом (№4 `mai_ml`, №10 карта CAPI, №11 `predicted_value`, №12 ротация токена, №13 AES с оговоркой, №14 OPE-gate, №15 `min_margin`), 3 частично (№1 `event_id` — отказ на ингесте есть, колонка nullable, дедуп только Redis TTL 24 ч без `ON CONFLICT`; №2 `schema_version` — DEFAULT есть, NOT NULL нет; №9 пути — старые исправлены, появились новые), 4 закрыты только правкой документа под код (№3 GUC, №5 decay, №6 PosteriorRecalc, №7/№8 маршруты), 3 не закрыты (№1 по сути, №16 `X-Business-Use-Case-Usage` — grep 0, №17 `call_objections.call_id` — V110 ссылается на `call_note_id`, документ не исправлен).

**§3 — «не реализовано» (19):** закрыто 16 (GHL polling, CVR-collapse — пауза только в БД, MMF-scan, weekly OPE, авто-партиции, `persona_mode` и `capi_value_mode` — без API записи, экспертный рейтинг, self-report, `expected_value_usd`, FORM_NO_SLOT, `blocked_by_meta` recovery, slot capacity, Reg CF, cluster features, логин, админ-UI). Не закрыто: hourly anomaly-scan (`/signals/anomaly` из Java не вызывается, `architecture.md:867` утверждает обратное). Частично: `launchTestBatch(moderationProbe=true)` — poll есть, но единственный вызов `TwoContourService.java:75` передаёт `false`, `true` недостижим через API.

**§4 — vision:** AI-видео/TTS/субтитры закрыты (без ключей — честные стабы); GLM → Thompson закрыто только для новых плеч; EVI/top-two закрыто; Markowitz в ML есть, Spring зовёт только `/optimize`; иерархическая PyMC, hurdle/LKJ, MLflow (`requirements.txt:27` закомментирован), эмбеддинги (md5-стаб без ключа), Geo-Lift (один scale-коэффициент) — не закрыты.

**§7 — 8 рекомендаций:** 7 закрыты, №8 (обновить §5/§9 architecture) частично: `:1358`, `:1661`, §16.11/16.21 не обновлены; заголовок документа «Version 1.5 — 2026-10-20» при фактическом v1.7 (`architecture.md:3`).

Все тесты, заявленные в roadmap 9-x (12 Java, 8 ML), существуют как файлы.

### 8.2. Roadmap: отмеченное без кода или частично

Из 29 пунктов Phase 10/11 и 44 выборочных Phase 8/9: подтверждено полностью 58, частично 13, без кода 1.

| Пункт | Заявлено | Факт | Доказательство |
|---|---|---|---|
| 11-4a | DRIFT → «arm frozen until a human decides» | **без кода**: только текст алерта и `publish_status='DRIFT'`; ML не знает `DRIFT`/`UNMANAGED` | `MetaAdReconcileScheduler.java:116-120`; `ad_map_service.py:23-24` не фильтрует по статусу |
| 9-19 | «`MetaWriteGate` — single choke point: all 9 writes» | **частично (P1)**: write-методов в `MetaApiService` 10; `upsertCustomAudience` вызывается из `MetaAudienceController.java:135` без gate (= M-1) | подтверждено |
| 11-4c | «red → test launches blocked (`GateEvaluationService`)» | частично: блок в `MetaWriteGate.java:73-79` и только для `LAUNCH`; в `GateEvaluationService` слова `linkage` нет | |
| 11-5a | «/ab-results и /explain показывают пару» | частично: `/ab-results` да; `/explain` пар не знает, во фронте не используется | |
| 11-2b, 11-3e | publish_status «shown on `/creatives`», кнопка «Publish to Meta» на `/creatives/{id}` | частично: всё это на `/meta-ads`; `/creatives*` — страницы landing-вариантов | `meta-ads/page.tsx:171-201` |
| 11-2b | Набор `publish_status` | в коде шире (`APPROVED_PAUSED`, `DRIFT`), CHECK в V136 отсутствует | `V136:45` |
| 11-3b | «dynamic creative … OFF» | только `standard_enhancements OPT_OUT`; dynamic creative явно не выключается | `MetaApiService.java:356-358` |
| 11-3c | publish требует «approved render» | в readiness-листе проверки рендера нет | `CreativePublishService.java:92-107` |
| 9-12 | «Spring caller via `PortfolioController`» | зовёт `/portfolio/optimize`, не `/markowitz` | `PortfolioController.java:118` |
| 9-8 | CvrCollapse «P0 autopause» | пауза только в БД (= D-1) | |
| 9-15 | render `music: {track_id,…}` | обязателен `track_url`, `track_id` опционален | `video.py:276-277` |
| 9-16 | «Spring caller … still to add» | устарело в обратную сторону — `AiVideoController.java:200-230` есть | |
| 8-15 | `POST /propensity/noshow`, `/slot-fill` | таких путей нет; есть `/propensity/score`, `/fit`, `/report` | |
| 10-2a | «JWT-guarded» preview | guard клиентский (`AuthGuard.tsx`); данные защищены 401 на API | |
| 10-4b | rate limit per key | in-memory, 600/мин **на инстанс**, карта не чистится | `PublicLandingController.java:43-44,130-138` |
| 10-4c | `COLLECT` домен | только значение `kind` в V135; логики first-party сбора нет | |

**Bug List** (`roadmap.md:1399-1401`): `_(empty — project not started)_` — устаревшая заглушка при 136 миграциях. Реальные открытые ⚠ разбросаны по фазам.

### 8.3. Architecture v1.7 ↔ код

Фактические числа: Java-маршрутов **340** (89 контроллеров), ML-путей **148** (48 роутеров; документ: 139/147), scheduler-классов **56** (документ: 55).

| § | Утверждение | В коде | Доказательство |
|---|---|---|---|
| заголовок | «Version 1.5 — 2026-10-20» | блоки v1.6/v1.7 добавлены, версия в шапке не поднята | `architecture.md:3,17-18` |
| 5.1 | `POST /capi-events` принимает webhook | только GET | `CAPIEventController.java:17,24,33` |
| 5.1 | `GET /offer-schemas/{key}/explore` | `@PostMapping` | `OfferSchemaController.java:78` |
| 5.1 | — | **баг кода**: `@PostMapping("/api/v1/campaigns/{id}/postmortem")` без class-mapping при `context-path: /api/v1` → реальный URL `/api/v1/api/v1/...` | `PostmortemController.java:24` |
| 5.1 | 42 контроллера (~160 маршрутов) не упомянуты | `MetaCampaignController` (14), `VideoController` (13), `BriefController` (11), `AgencyController`, `AiVideoController`, `MusicTrackController`, `Month1Controller`, `PostCallController`… | |
| 5.2 | «139 routes, every one with a Spring caller» | 148 путей; **68 без вызова из Java** (`/quality/score`, `/signals/anomaly`, `/glm/*`, `/taxonomy/align`, `/anchors/*`, `/persona/build`, `/model-quality/*`, `/portfolio/markowitz`, `/decide/evi`…); `check-ml-routes.py` проверяет только Java→ML и 3 паттерна, не видит `mlClient.post(`, `mlPost(`, `callFastApi(` | `scripts/check-ml-routes.py:24-44` |
| 5.2 | `POST /predict/score` | нет; есть `/predict/cpm`, `/predict/budget-curve` | |
| 5.2/16.21 | `POST /propensity/noshow`, `/predict/lead-value` | в коде под prefix `/decide` | `main.py:42` |
| 5.2 | — | **баг кода**: `/decide/decide/funnel-path` (= ML-8) | `decide.py:690` |
| 9 | Baseline anomaly scan → `/signals/anomaly` | вызывается только `/signals/news-sentiment` | `ExternalSignalsScheduler.java:133` |
| 9 | «External signals: hourly (CPM, news)» | news — daily 06:30 | |
| 9 | «QualityScoreScheduler → FastAPI `/quality/score`» | считается в Java | |
| 9 | 55 классов | 56; в §9 не перечислены 17 | |
| 9/16.11/16.21 | Follow-up recalc 15 min, Lead-value refit weekly, Archetype refit weekly, Learning-phase snapshot «on every write», Blocked-creative «on insert» | нет / опрос вместо события | |
| 4.5 | `event_id NOT NULL`, `schema_version NOT NULL DEFAULT 1`, индекс без условия | nullable, DEFAULT без NOT NULL, partial-индекс | `V11:3`, `V24:8-13`, `V128:13-14` |
| 4 | Таблицы V127–V136 | V135/V136 описаны; **нет**: `music_tracks` (V131), `capi_value_mode_runs` (V132), `meta_write_decisions`, `meta_write_overrides`, `tenants.meta_write_stage/policy` (V133), `render_feedback` (V134), `creatives.is_ai_generated/ai_provider` (V129), V130-колонки, `ensure_events_partition` (V128) | grep = 0 |
| 16.11 | `GET/PUT /tenants/me/funnel`, `GET /funnel/stages/{contactId}`, `PUT /compliance/rulebases`, `/tenants/me/offer-schema`, `POST /creatives/aliases` | `GET /funnel`, `/funnel/me`, `POST /funnel/stages`; только GET rulebases; `/offer-schemas`; `/creative-setup/aliases` | |
| 16.21 | `PUT /funnel-paths`, `GET /noshow/scores`, `/booking/capacity-policies`, `/booking/window`, `/lead-value/calibration`, `POST /calls/recordings`, `/calls/objections`, `/archetypes/policies`, `/creative-sources`, `/reports/sources`, `/waitlist/value` | ни один путь не совпадает: `/propensity/booking-window`, `/form/calibration-report`, `/webhooks/call-recordings/{tenantId}`, `/post-call/objections`, `/investor-archetypes/policies`, `/agency/sources`, `/agency/source-report`, `/regcf-waitlist/value` | |
| 10 | Docker Compose | устарел: ml-service как `mai`, порт 8000 на всех интерфейсах, нет `mlflow`, `DB_URL` не читается кодом | `architecture.md:887-943` |
| 17 | режимы, TXT `_mai-verify`, CNAME-target, `/public/decide` + `X-Runner-Key` | подтверждено; CNAME-target по умолчанию `landings.example.com` | `application.yml:61` |
| 18 | chunked → OPT_OUT → PAUSED; reconcile; moderation; PREVIEW_ONLY; linkage gates LAUNCH | подтверждено; «arm frozen» при DRIFT — механизма нет; MANAGED/RECONCILED/UNMANAGED/MANUAL в §18 смешаны (в схеме `source` и `status` раздельно) | `MetaAdMap.java:52,56` |

### 8.4. Capabilities: фактические метрики

| Метрика | В документе | Фактически |
|---|---|---|
| Тесты Java | 295 | **295** (прогон 2026-10-22: 295 passed) |
| Тесты ML | 1097 | **1088** `def test_` + параметризация; прогон: **1097 passed** |
| Тесты Frontend | нет | 0 файлов |
| Миграции | V1–V136 | **136** |
| ML роутеры / модули | 48 / 81 | **48** / **83** |
| Java контроллеры / шедулеры | 84 / 55 | **89** / **56** |
| Frontend страницы | 23 (17 админских) | **25** (**19** в `(admin)`) |
| Блоки лендинга | 14 | **14** |

**Заявлено как работает — в коде стаб или флаг выключен:**

| Возможность | § capabilities | Код | Факт |
|---|---|---|---|
| Байесовский MMM (adstock, Hill) | §6.5 | `mmm_service.py:3` «PyMC imported lazily» — импорта PyMC нет; `MmmController.java:77,86` вклад = доля расходов | **не байесовский, не подгоняется** |
| Compliance violations в Month-1 | §11.2 | `Month1Controller.java:173-176` `'No violations recorded' — placeholder` | всегда «нарушений нет» |
| Диагностика воронки | §3 | `AnalyticsService.java:303` «Phase 3 placeholder symptoms»; `diagnostics.py:138` | симптомы всегда «не обнаружены» |
| reCAPTCHA Enterprise | §7.2 | `RecaptchaFilter.java:15-24,59-61` | **fail-open** без ключа/заголовка/при ошибке |
| Cloudflare Bot Score | §7.2 | `EventController.java:36` читает `CF-Bot-Score` | заголовок не верифицируется как пришедший от Cloudflare |
| Генерация контента LLM | §10.1 | `content_service.py:86-88` `"model_version": "stub"`; `LandingSetService.java:130` placeholder | без ключа лендинг-сеты из «[Draft HERO content]» |
| Whisper / сцены / монтаж-план | §2.1, §2.8 | `STUB_TRANSCRIPT`, `STUB_SCENES/STUB_PLAN` | без ключа стаб-текст уходит в Creative DNA без пометки в БД |
| Эмбеддинги | §10.4 | `taxonomy_service.py:23-25` md5-хэш | без ключа кластеры бессмысленны, но записываются |
| Geo-Lift «синтетический контроль» | §8.4 | `geo_lift_service.py:44-55` один scale-коэффициент | формулировка завышена |
| LightGBM «второе мнение» CPM | arch §15.17 | `cpm_service.py:107-112` «MVP stub: log-linear» | расхождение моделей сымитировано |
| MLflow | arch §15.10 | `mlflow_service.py:3-4`; `requirements.txt:27` закомментирован | всегда no-op; контейнер `mlflow` в compose бесполезен |
| Марковиц | §9.8 | Spring зовёт `/optimize` (Шарп) | продуктовый путь — Шарп |
| Еженедельный e-mail | §11.1 | `application.yml:26` `MAIL_HOST:` пусто | письмо молча не уходит |
| Алерты Telegram/Slack | §7.1 | `AlertService.java:30-47` | в compose переменные не заданы → все алерты в `log.warn` |
| Playwright form check | §12.5 | `Dockerfile:16 INSTALL_PLAYWRIGHT=0` | `"method": "unavailable"` |
| Аудит-журнал Meta «append-only» | §12.6 | `V47:25` только GRANT; триггера нет | неизменяемость только правами (и те сняты V116, D-17) |
| L2 автопауза через Meta | §7.1 | `CircuitBreakerService.java:240` «we don't pause ACTIVE ad sets at L2» | L2 только блокирует новые запуски |

### 8.5. Инфраструктура и репозиторий: находки

| ID | Серьёзность | Где | Что не так | Доказательство | Как исправить |
|---|---|---|---|---|---|
| X-1 | **P0** | `amazon bedrock settings.txt` (tracked) | **Живые секреты в git**: AWS access key id и secret, Anthropic API key и ещё один токен. В истории с коммита `ce9904a` (2026-10-17). Remote отсутствует — утечка пока локальная, но любая копия репозитория или первый push раскроет | `git ls-files` → файл; `git grep -c -E 'AKIA[0-9A-Z]{16}'` = 1, `sk-ant-` = 1; `git log --follow` → `ce9904a` | Немедленно ротировать AWS- и Anthropic-ключи; `git rm --cached`, в `.gitignore`; переписать историю (`git filter-repo`) до любого push; gitleaks в pre-commit |
| X-2 | **P0** | `docker-compose.yml:6,23` | Postgres `5432:5432` и Redis `6379:6379` опубликованы на всех интерфейсах; Redis без пароля; пароль владельца БД захардкожен; prod-профиля нет | `grep requirepass` — 0; `application-prod.yml` отсутствует | `127.0.0.1:`, `requirepass`, секреты через `.env`, prod-профиль |
| X-3 | **P0** | весь репозиторий | Бэкапов БД нет: ни `pg_dump`, ни WAL-архива, ни скрипта; единственный volume `pgdata` | grep `pg_dump|backup|wal` — 0 | cron `pg_dump` + offsite (R2/B2), проверка восстановления |
| X-4 | **P0** (= S-2) | `TokenVault.java:47-48`; compose | Документы обещают «AES в БД», но без `ENCRYPTION_KEY` токены пишутся открыто; ни compose, ни README, ни `.env.example` переменную не упоминают | | Fail-fast вне `local`; добавить в compose/README |
| X-5 | P1 (= M-1) | `MetaAudienceController.java:135` | Запись в Meta вне `MetaWriteGate` | | см. M-1 |
| X-6 | P1 (= S-9) | `RecaptchaFilter.java:21-24,59-61` | Fail-open | | Fail-closed для публичных mutating-эндпоинтов при заданном ключе |
| X-7 | **P1** | `scripts/verify-migrations.sh:18` | Передаёт только `-placeholders.app_role_password`; V127 требует `${ml_role_password}` → Flyway упадёт на V127. Скрипт сломан с 2026-10-20 (`check-sql.py` и `verify-rls.sh` исправны) | `V127:15` | Добавить `-placeholders.ml_role_password=` |
| X-8 | **P1** | `application.yml:5`, `application-local.yml:26-27`, `docker-compose.yml:38` | Единственный профиль `local` (= дефолт) включает `org.springframework.security: DEBUG` и `com.marketingai: DEBUG`; `logback-spring.xml` нет → нет ротации/JSON; ML без `logging.config` | | Профиль `prod` с INFO, logback с ротацией |
| X-9 | **P1** | CI/CD | `.github/workflows` отсутствует; pre-commit нет; 1392 теста нигде не запускаются автоматически | `find .github -type f` → только артефакты VS Code-расширения | GitHub Actions: gradle test, pytest, tsc, check-sql, verify-rls, gitleaks |
| X-10 | **P1** | `ml-service/Dockerfile:27`, нет `.dockerignore` ×3 | `COPY . .` без `.dockerignore` → локальный `ml-service/.env` (с ключами) и `.venv` попадут в образ; `platform-api` и `ml-service` работают от root | | `.dockerignore`, `USER` |
| X-11 | **P1** | `docker-compose.yml` | Нет `restart:`, `deploy.resources`, healthcheck у `platform-api`/`ml-service`/`frontend`; `JWT_SECRET` захардкожен `:50`; плавающие теги образов; `frontend` `NODE_ENV: development` при `production` в Dockerfile | | Pin, restart policy, лимиты, healthchecks |
| X-12 | **P1** | `docker-compose.yml:101-119` + `requirements.txt:27` | `mlflow` пишет в ту же БД `marketingai` под владельцем `mai`; пакет `mlflow` не установлен, URI не передаётся → контейнер бесполезен | | Убрать или отдельная БД + клиент |
| X-13 | **P1** | `README.md`, `.env*.example` | README: Spring Boot «3.3» (факт 3.5.0); нет `platform-api/.env.example`; не упомянуты `ENCRYPTION_KEY`, `META_APP_ID/SECRET`, `TELEGRAM_*`, `SLACK_WEBHOOK_URL`, `R2_*`, `MAIL_*`, `RECAPTCHA_SECRET_KEY`, `CORS_ALLOWED_ORIGINS`, `LANDING_CNAME_TARGET`, `PLATFORM_HOSTS`, `DB_ML_PASS`; `ml-service/.env.example` без ключей провайдеров и `LLM_MODEL_*`; `frontend/.env.local.example` без `API_INTERNAL_URL`, `NEXT_PUBLIC_RECAPTCHA_SITE_KEY`, `NEXT_PUBLIC_COOKIEBOT_DOMAIN_ID`, `PLATFORM_HOSTS` | | Полные `.env.example`, README «запуск с нуля» |
| X-14 | P2 | `PostmortemController.java:24` | `/api/v1/api/v1/campaigns/{id}/postmortem` | подтверждено | Убрать префикс |
| X-15 | P2 (= ML-8) | `decide.py:690` | `/decide/decide/funnel-path` | | Исправить |
| X-16 | P2 | `scripts/check-ml-routes.py:24-44` | Не видит `mlClient.post(`, `mlPost(`, `callFastApi(`; обратную проверку не делает → «every one with a Spring caller» недостоверно | | Расширить паттерны, обратная проверка |
| X-17 | P2 | `platform-api/bin/` (63 файла tracked), `frontend/tsconfig.tsbuildinfo` (tracked, постоянно M), `.ai/all-code-files.md` (2.1 МБ дамп исходников, tracked, удалён локально без коммита), `temp.txt`, `.vscode/settings.json` | Артефакты сборки и временные файлы в git | `git ls-files` | `git rm --cached`, `.gitignore` |
| X-18 | P2 | `health.py:6-8`; actuator | ML `/health` без проверки БД/Redis; Prometheus не подключён; actuator только `health,info` | | Health с ping, `/actuator/prometheus` |
| X-19 | P2 (= S-14, S-9) | `SecurityConfig.java:39` | `/files/**` permitAll; `/events`, `/auth/**`, `/public/**` без rate-limit; brute-force защиты логина нет | | Подписанные URL, rate limit |
| X-20 | P2 (= D-17) | `V47:25` | «append-only» только через GRANT, триггера нет | | Триггер `BEFORE UPDATE OR DELETE … RAISE` |
| X-21 | P2 | `architecture.md:887-943`, `:3`, `:1358`, `:1661`; `creative-publishing.md:3` «код — Phase 11 (не начат)»; `roadmap.md:1399` | Устаревшие фрагменты документации | | Обновить |
| X-22 | P2 | `архив/*.md` (tracked), 18 PDF + 9 HTML в корне, `.ai/*.pdf`, 4 `generate_*_pdf.py` в корне | Дубли и мусор | | Удалить `архив/`, генераторы в `scripts/` |
| X-23 | P2 (= ML-14) | `brief.py:402`, `competitive.py:94`, `signals.py:141`, `video.py:167,243`, `voc.py:89` (`gpt-4o-mini`), `taxonomy_service.py:37`, дефолты в `content.py`, `compliance.py`, `explain.py` | Литералы model-id вместо `settings.llm_model_*`; `llm_model_default` не используется ни разу. В документах: `roadmap.md:200` `claude-opus-4-5`, `:238` `claude-haiku-4-5` | | Все вызовы через settings |
| X-24 | P3 | TODO/FIXME | Формальных почти нет (Java 0, ML 1, frontend 0); долг живёт в комментариях `placeholder/stub/deferred` | | |
| X-25 | P3 | Лицензии | Copyleft нет; `redis:7-alpine` без minor — с 7.4 лицензия RSALv2/SSPL (закрепить 7.2 или valkey); `react 19.0.0-rc.1` в проде | | |
| X-26 | P3 | git | Ветка `master`, remote нет; `.gitignore` `*.html` + `!.ai/` | | |

Скрипты: `verify-rls.sh`, `check-sql.py`, `check-ml-routes.py` (с оговоркой X-16), `md_to_pdf.py` — рабочие; `verify-migrations.sh` сломан (X-7); `build_audit_with_code.py` и `generate_response_pdf.py` устарели. Алерты: только Telegram/Slack, 30 вызовов из 27 классов; e-mail/PagerDuty нет; в compose ни одна переменная не задана → все алерты уходят в `log.warn`.

### 8.6. Итог по области

Семь из семнадцати расхождений прошлого ревью закрыты кодом, четыре — переписыванием документа под код, три не закрыты. Roadmap Phase 10/11 подтверждён на уровне классов, миграций и страниц, но пять отмеченных пунктов преувеличивают: «arm frozen при DRIFT» механизма не имеет, `MetaWriteGate` обходится через `upsertCustomAudience`, `/explain` пар не знает, кнопка публикации живёт не там, где написано. Architecture §17/§18 точен, но §5 описывает около 120 из 340 Java-маршрутов, §16.11/16.21 содержат около 12 несуществующих путей, §5.2 утверждает «у каждого ML-маршрута есть Spring-вызов», тогда как 68 из 148 никем не вызываются. Метрики capabilities честны, но ряд возможностей за фасадом — стабы: MMM без PyMC, Month-1 «нарушений нет» захардкожен, диагностика воронки — placeholder, reCAPTCHA fail-open, MLflow no-op. Инфраструктурно проект к продакшену не готов и содержит P0-утечку: AWS- и Anthropic-ключи в отслеживаемом файле с коммита `ce9904a`; плюс Postgres/Redis наружу без пароля, нет бэкапов, нет prod-профиля (DEBUG security-логи по умолчанию), нет CI, `verify-migrations.sh` сломан с V127, `.dockerignore` отсутствуют, а обещанное шифрование токенов фактически выключено в единственном имеющемся compose.
