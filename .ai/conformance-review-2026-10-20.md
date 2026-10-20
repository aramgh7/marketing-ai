# Ревью соответствия кода architecture.md v1.4 и vision-public.md — 2026-10-20

Проверка всего репозитория (platform-api, ml-service, frontend, миграции V1–V126) против
`.ai/architecture.md` (§1–§16) и `.ai/vision-public.md` (§1–§26). Каждое утверждение ниже
подтверждено grep'ом по коду; выводы ревью‑агентов, которые не подтвердились, исключены.

## 1. Итог

| Область | Оценка |
|---|---|
| Ключевые инварианты архитектуры (§4, §6, §7, §8, §12) | **Соответствует** — 31 проверка из 31 (RLS + FORCE + `mai_app`, единственный писатель `thompson_arms`, propensity‑лог до ответа `/decide`, Spring — единственный отправитель CAPI, внутренний ключ на всех ML‑роутерах, целочисленные α/β) |
| Таблицы §4 | Соответствует; 3 мелких расхождения (nullable‑колонки) |
| Маршруты §5 | ~75 % по именам; 22 расхождения в путях/методах, 11 маршрутов из документа отсутствуют (функции есть под другими путями) |
| Джобы §9 | 19 из 25 есть; 4 перенесены из FastAPI в Spring; 6 отсутствуют |
| §15 (20 модулей) | 14 реализовано, 6 частично, 0 отсутствует |
| §16 (21 раздел) | 15 реализовано, 6 частично; 2 семантических расхождения |
| vision-public.md | Ядро (§2–§7, §11–§13, §15–§18, §21–§24) реализовано; системные пробелы — в разделе 6 |
| Верификация | ML 954/954, Java 205/205, `tsc --noEmit` чисто, check-sql 499/500 (1 неизвестный тип параметра), ML‑маршруты 139 / 0 без Java‑вызова; 126/126 миграций применялись ранее в этой сессии |

Полный запуск стека через docker compose в этой сессии **не выполнялся**: диск C: заполнен
(232 МБ свободно), сборка ML‑образа требует ~3 ГБ.

## 2. Расхождения с architecture.md (подтверждены)

| # | Где | Документ | Код | Значимость |
|---|---|---|---|---|
| 1 | §4.5 `events.event_id` | `NOT NULL`, уникальный индекс без условия | nullable, индекс `WHERE event_id IS NOT NULL` (V24); дедупликация — Redis с TTL 24 ч (`EventIngestionService.isDuplicate`), INSERT без `ON CONFLICT` | средняя: после 24 ч или сброса Redis повтор батча упадёт на уникальном индексе |
| 2 | §4.5 `schema_version` | `NOT NULL DEFAULT 1` | nullable без default (V46) | низкая |
| 3 | §8 GUC | `SET LOCAL` в транзакции | `set_config(..., false)` на сессию + сброс при возврате соединения (`TenantScopedConnection`); причина — autocommit Hikari | низкая (безопасно, но не как в документе) |
| 4 | §8 роль ML | оба сервиса — `mai_app` | ml-service подключается как владелец `mai` (docker-compose, V116 это признаёт); FORCE RLS действует и на владельца | средняя: ML не ограничен REVOKE'ами для `mai_app` |
| 5 | §9 decay | ежедневно 03:00 UTC | лениво в `load_arms()` при `last_decayed_at > 24 ч`; незагружаемые плечи не затухают | низкая |
| 6 | §9 recalc | батч каждые 15 мин | инкрементально через `POST /decide/reward` + `RewardSweepScheduler`; `PosteriorRecalcScheduler` только логирует | низкая (другая, рабочая схема) |
| 7 | §9 Quality Score / Taxonomy / Proxy calibration | FastAPI | Spring‑шедулеры, вызывающие ML | нет |
| 8 | §5.2 `/decide` | `POST /decide` | `POST /decide/landing`; `/decide/batch`, `/model/update`, `/model/decay`, `/model/posteriors` нет | документ устарел |
| 9 | §5 пути | `/events/capi`, `/analytics/variants`, `GET /tenants/me`, `PUT /signals/calendar`, `/offers/*`, `/explain/{tenant}/{entity}`, `/predict/cpm`, `/diagnostics/{id}/power` | `/capi-events`, `/analytics/creatives`, `GET /tenants/me/settings`, `POST /signals/calendar`, `/mmf/offers/*` и `/evolve/*`, `POST /explain/{entity_type}`, `/predict/score`, `/simulate` | документ устарел |
| 10 | §16.3 карта CAPI | FORM_SUBMIT→Lead, BOOKED→Schedule, CALL_HELD→CompleteRegistration, FUNDS_RECEIVED→Purchase | `ValueFormulaService.seedInvestorCAPIMap`: BOOKED→Lead, CALL_HELD→Schedule, ACCRED_VERIFIED→CompleteRegistration, INVEST_COMMITTED→Purchase | **нужно решение**: код совпадает с platform-capabilities §5.2, документ архитектуры — нет |
| 11 | §16.16 `predicted_value` | токен в `value_formula` | `ValueFormulaService` подставляет только `k_accr`, `k_ticket`; прогноз хранится в `lead_value_predictions`, в CAPI не уходит | средняя |
| 12 | §15.15 ротация токенов | обмен long‑lived токена на 50‑й день | `TokenRotationScheduler.attemptTokenRotation()` всегда `return false` (заглушка); понижение автономии при INVALID не делается | **высокая** для прод‑запуска |
| 13 | §11 токены Meta | AES в БД | `EncryptionService` (AES‑256‑GCM) есть, но нигде не вызывается; `meta_access_token_enc` / `capi_access_token_enc` / `ghl api_key_enc` — plain bytes | **высокая** |
| 14 | §15.11/15.13 OPE‑gate | апгрейд автономии только после OPE PASS | `AutonomyService` / `AutopilotController` не проверяют `ope_runs`; OPE только по запросу | **высокая** (обещано в capabilities §9.6) |
| 15 | §15.6 | `load_arms()` исключает варианты ниже `min_margin` | фильтр только в `offer_validity_service` при сохранении | низкая |
| 16 | §15.20 | размер бакета из `X-Business-Use-Case-Usage` | фиксированный счётчик | низкая |
| 17 | §16.18 | `call_objections.call_id → call_recordings` | V110 ссылается на `call_note_id`; `call_recordings` появился в V125 | низкая |

## 3. Не реализовано из architecture.md

**§9 джобы:** GHL polling‑sync (60 мин) — данные только из вебхуков; детектор CVR‑collapse (15 мин); базовый hourly anomaly‑scan как джоба (есть `POST /signals/anomaly` по запросу); ежедневный MMF‑scan; еженедельный OPE; авто‑создание партиций `events` (созданы вручную до 2027‑03, см. V5).

**§15:** `persona_mode` (OFF/LOG_ONLY/AB_TEST/PREFILTER) и `batch.persona_arm`; экспертный рейтинг → сдвиг Beta‑приора; `capi_value_mode = PROXY|FIXED|AB`.

**§16:** self‑report‑калибровка P(ACCRED_VERIFIED | self_accredited) (еженедельно); обновление `expected_value_usd` по закрытым когортам; аудитория `FORM_NO_SLOT`; recovery‑джоба для `blocked_by_meta`; обновление ёмкости слотов (15 мин); `launchTestBatch(moderationProbe=true)` и опрос `ad_review_feedback` (30 мин); еженедельная оценка Reg CF waitlist (есть только GET); `ticket_bucket`/`self_accredited` как признаки кластеризации.

**Фронтенд:** нет страницы логина — `storeAuthResponse` в `lib/api.ts` никем не вызывается, токен можно положить только вручную в `localStorage`; админка — 13 страниц, нет UI для пикселей, compliance‑override, autonomy, sub‑аккаунтов GHL.

## 4. Расхождения с vision-public.md (системные)

| Vision | Состояние |
|---|---|
| §5.2 AI‑видео, TTS, озвучка | `ai_video_service.py` только собирает payload HeyGen/Synthesia/Runway, HTTP‑вызовов нет; TTS/музыка не генерируются; субтитры — один drawtext‑оверлей на сегмент, SRT/ASS из таймкодов Whisper нет |
| §5.2 монтаж | есть: Whisper → tag‑scenes → montage‑plan (Claude) → `POST /video/render` (ffmpeg, 9:16/4:5/1:1, drawtext) |
| §15.6 Thompson из GLM‑постериоров | Thompson берёт Beta(α,β) из счётчиков; GLM‑постериоры (`model_posteriors`, PyMC) в выбор плеча не подключены |
| §15.4 трёхуровневая иерархическая модель | `hierarchy_prior_service.py` считает приоры universal→niche→client, но совместной межклиентской PyMC‑модели нет; LKJ‑корреляции отложены (комментарий в `bayesian_glm.py`) |
| §15.5 hurdle | CTR, CVR, AOV — три отдельных PyMC‑модели, не один совместный постериор |
| §6.4 EVI / top‑two Thompson | нет |
| §8.5 портфельная оптимизация | `portfolio_service.py` за флагом, Markowitz нет (Phase 6+) |
| §12.6 L1 edge‑фильтр | Cloudflare bot‑score читается из заголовка; reCAPTCHA Enterprise — реальный клиент; sGTM не развёрнут (по решению v1.1) |
| §15.10 MLflow | логирует метрики при `MLFLOW_TRACKING_URI`, `register_model` не вызывается |
| §15.12.11 эмбеддинги | OpenAI `text-embedding-3-small`; без ключа — детерминированный hash‑стаб; колонка `creative_atoms.embedding` не маппится в JPA |
| §12.1a лица/музыка | `media_compliance_service.py` читает `detected_faces` из входа; Rekognition/MediaPipe не вызываются |
| §17.3 Geo‑Lift | собственный bootstrap synthetic control, не CausalImpact; Meta Experiments API нет |
| §26 Google Ads / TikTok | REST‑клиенты (`GoogleAdsApiService` v17, `TikTokApiService` v1.3) написаны, на живых аккаунтах не проверялись |

## 5. Утверждения platform-capabilities.md, не подтверждённые кодом (исправлены в документе)

1. «Переход выше ADVISOR — только с прохождением OPE‑gate» — gate не реализован.
2. «Токены Meta API … предупреждение за 8 дней, алерт за день» — в коде: попытка ротации с 50‑го дня (заглушка), INVALID + алерт + пауза записей в Meta с 58‑го дня.
3. «Статус: Production‑ready» — заменён на честную формулировку (пилот после внешней настройки, известные ограничения).
4. Метрики раздела 15 (849 тестов, V113, 25+ сервисов, 35+ контроллеров) — обновлены до фактических.
5. §5.3 «передаём predicted_value в Meta» — прогноз считается и хранится, в CAPI‑value не подставляется.
6. §2.8 «Голос как атом: тон, темп, тембр извлекаются» — извлекаются из настроек провайдера, не из аудио.

## 6. Исправлено в этой сессии (в рамках ревью)

- `ml-service/app/config.py`: единые `llm_model_fast` / `llm_model_default` / `llm_model_vision`; устаревшие `claude-opus-4-5` (×2, `llm_client.py`) и `claude-haiku-4-5` (`routers/voc.py`) заменены на настройки. Остальные вызовы уже использовали `claude-haiku-5-5` / `claude-sonnet-5-5`.

## 7. Рекомендуемый порядок доработок

1. Подключить `EncryptionService` к записи/чтению `meta_access_token_enc`, `capi_access_token_enc`, `ghl_subaccounts.api_key_enc` (+ миграция данных).
2. Реализовать обмен токена в `TokenRotationScheduler` и понижение автономии при INVALID.
3. OPE‑gate в `AutopilotController.PUT /settings` (последний `ope_runs` PASS не старше 7 дней) + еженедельный `OpeScheduler`.
4. Страница логина во фронтенде (`/login` → `/auth/login` → `storeAuthResponse`).
5. Согласовать карту CAPI (§16.3 документа vs `ValueFormulaService`) и поддержать токен `predicted_value`.
6. Перевести ml-service на роль `mai_app` (отдельный GRANT на `thompson_arms`).
7. Субтитры из таймкодов Whisper (ASS/SRT → ffmpeg) и один TTS‑провайдер — по решению заказчика.
8. Обновить §5 и §9 architecture.md под фактические пути и расписания (см. раздел 2, пп. 5–9).
