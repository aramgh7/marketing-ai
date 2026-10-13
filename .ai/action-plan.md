# Marketing AI Platform — Action Plan

> Master checklist: что делать и в каком порядке.
> Включает и код, и бизнес-процессы, и подготовку к первому клиенту.
> Обновляется по мере продвижения.

---

## Этап 1 — Подготовка (до кода)

### Документация
- [x] Vision document (vision.md v1.9.6)
- [x] Architecture document (architecture.md v1.0)
- [x] Roadmap (roadmap.md v1.0)
- [x] Action plan (этот файл)
- [ ] Test strategy (test-strategy.md) — правила тестирования, аналог Sales-ai

### Бизнес-решения
- [x] Бизнес-модель: SaaS
- [ ] Ценообразование: тарифные планы (Free trial? Starter / Pro / Enterprise?)
- [ ] Юридическая структура: договор с клиентом (шаблон)
- [ ] Data sharing clause в договоре (для будущей Universal Model, §15.12.7)

### Первый клиент (B2B гидрогели)
- [x] CRM клиента: **GoHighLevel** (GHL)
- [x] Кто делает креативы: **сами** (нет агентства/фрилансеров) → Creator Brief (§5.5) = главный инструмент с Phase 1; Phase 4 (видеомонтаж вариаций из их footage) = прямая экономия времени
- [x] Куда ведёт реклама: **на лендинг** → Phase 2 (Landing Engine + Thompson) запускается без зависимостей; полный контроль трекинга с первого дня
- [x] Что считается качественным лидом: **ответил на звонок** + **пришёл на встречу** (Google Calendar) → 4-ступенчатая цепочка CAPI (§16.1)
- [ ] Получить доступ к Meta рекламному кабинету клиента (Business Manager)
- [ ] Собрать информацию о продукте: описание, УТП, целевая аудитория, цены
- [ ] Получить существующие креативы (видео, баннеры) для тестовой загрузки
- [ ] Узнать текущий рекламный бюджет и целевые метрики (CPA, ROAS)
- [ ] Понять воронку продаж: лид → квалификация → сделка (цикл, этапы) — уточнить средний чек и цикл
- [ ] Согласовать домен для лендингов (поддомен клиента или наш?)

---

## Этап 2 — Внешние регистрации (параллельно с кодом)

### Meta App
- [ ] Создать Meta App на developers.facebook.com
- [ ] Настроить продукт "Marketing API" в приложении
- [ ] Запросить permissions: `ads_management`, `ads_read`, `business_management`
- [ ] Подать на App Review (занимает 2-4 недели)
- [ ] Настроить System User в Business Manager клиента
- [ ] Получить System User Token для серверных вызовов
- [ ] Создать и настроить Meta Pixel для клиента
- [ ] Настроить Conversions API (CAPI) endpoint

### Cloudflare
- [ ] Зарегистрировать аккаунт Cloudflare (если нет)
- [ ] Настроить домен(ы) для лендингов через Cloudflare (DNS + proxy)
- [ ] Активировать Bot Management (или Free tier challenge rules для MVP)
- [ ] Создать Cloudflare R2 bucket для медиа-файлов

### API ключи
- [ ] Anthropic API key (Claude для Creative DNA + LLM генерации)
- [ ] OpenAI API key (Whisper для транскрипции видео)
- [ ] Telegram Bot: создать бота для алертов, получить token
- [ ] Cookiebot: зарегистрировать аккаунт, получить CBID для домена клиента

### Хостинг (решить)
- [ ] Выбрать: GCP Cloud Run / AWS ECS / VPS (Hetzner/DigitalOcean)?
- [ ] Настроить PostgreSQL (Cloud SQL / RDS / managed / self-hosted?)
- [ ] Настроить Redis (Memorystore / ElastiCache / managed / self-hosted?)
- [ ] CI/CD: GitHub Actions workflow (test → build → deploy)
- [ ] sGTM: развернуть Server-Side GTM на GCP (или custom proxy)

---

## Этап 3 — Scaffold (код)

- [x] Инициализировать Git репозиторий
- [x] Создать Spring Boot проект (platform-api)
  - [x] build.gradle.kts с зависимостями (Spring Security, JPA, Flyway, Redis, Web)
  - [x] Application.java + application.yml / application-local.yml
  - [x] Security config (JWT)
  - [x] Dockerfile
- [x] Создать FastAPI проект (ml-service)
  - [x] main.py + requirements.txt
  - [x] Заглушки эндпоинтов (/decide, /analyze, /health)
  - [x] Dockerfile
- [x] Создать Next.js проект (frontend)
  - [x] App Router структура
  - [x] middleware.ts (заглушка)
  - [x] Dockerfile
- [x] docker-compose.yml (PostgreSQL + pgvector, Redis, 3 сервиса)
- [ ] `docker-compose up` — все сервисы стартуют и отвечают на health check
- [x] README.md с инструкцией запуска

---

## Этап 4 — Phase 0 (Foundation)

- [ ] Flyway миграции: все таблицы из architecture.md §4
- [ ] RLS policies на всех tenant-scoped таблицах
- [ ] Auth: register / login / refresh (JWT)
- [ ] TenantFilter + RLS integration test
- [ ] Event ingestion endpoint (POST /api/v1/events)
- [ ] Session management (создание/обновление)
- [ ] Fraud filter (behavioral session scoring)
- [ ] Budget guards (CRUD + scheduled check + Telegram alert)
- [ ] CMP integration (Cookiebot в frontend)

---

## Этап 5 — Phase 1 (Analytics Brain)

- [ ] Creative upload → R2 storage
- [ ] Creative DNA extraction pipeline (Whisper + Claude Vision)
- [ ] Creative atoms CRUD
- [ ] Dashboard: описательная аналитика, атомы, диагностическая карта
- [ ] Creator Brief шаблон
- [ ] Weekly auto-report

---

## Этап 6 — Phase 2 (Landing Engine + Thompson)

- [ ] Landing block React компоненты (Hero, Problem, Proof, Pricing, CTA, FAQ)
- [ ] Landing variant CRUD + approval workflow
- [ ] LLM content generation assist (текст блоков)
- [ ] Thompson Sampling engine (FastAPI /decide + Redis)
- [ ] Edge Middleware (Next.js → FastAPI)
- [ ] Client-side event tracker (tracker.ts)
- [ ] Conversion flow (form submit → event → arm update)
- [ ] Bayesian A/B dashboard
- [ ] RLHA (marketer edit tracking → few-shot learning)

---

## Этап 7 — Phase 3 (Decision Graph + Meta)

- [ ] Bayesian GLM (PyMC hurdle model)
- [ ] Meta Marketing API integration (create/pause/update campaigns)
- [ ] Two-contour structure (test + scale)
- [ ] Staged gates (sequential elimination)
- [ ] Full Circuit Breaker (L2-L3)
- [ ] CAPI: send conversions with value
- [ ] Exploration budget (10-20%)
- [ ] What-If Simulator
- [ ] Micro-holdout benchmark (3-5%)

---

## Этап 8 — Первый клиент: запуск

- [ ] Онбординг клиента: создать tenant, подключить Meta кабинет
- [ ] Загрузить существующие креативы клиента
- [ ] Извлечь Creative DNA
- [ ] Создать 3-5 вариантов лендинга
- [ ] Клиент апрувит лендинги
- [ ] Запустить Thompson Sampling на сайте
- [ ] Мониторинг первую неделю: events приходят? posteriors обновляются? fraud фильтр работает?
- [ ] Первый отчёт клиенту (неделя 2)
- [ ] Итерация: новые варианты на основе данных

---

## Параллельные треки (не блокируют основной поток)

| Трек | Когда начать | Зависит от |
|------|-------------|------------|
| Meta App Review | **Сейчас** | Ничего — чистый процесс |
| Сбор материалов первого клиента | **Сейчас** | Ничего |
| Выбор хостинга | До конца scaffold | Бюджет, предпочтения |
| sGTM настройка | Параллельно с Phase 0 | Хостинг, домен клиента |
| Юридическая подготовка | Параллельно | Ничего |
| Ценообразование SaaS | До запуска клиента | Бизнес-решение |

---

## Критический путь (что блокирует что)

```
Meta App Review (2-4 нед) ──────────────────────────────┐
                                                         ▼
Scaffold ──▶ Phase 0 (DB + Auth + Events) ──▶ Phase 1 ──▶ Phase 2 ──▶ Запуск клиента
                                                         ▲
Материалы клиента (креативы, продукт) ──────────────────┘
```

- **Scaffold** не зависит ни от чего — можно начать прямо сейчас
- **Phase 2** (Thompson на сайте) не зависит от Meta API — работает на нашем сайте
- **Phase 3** (Meta интеграция) блокируется Meta App Review
- **Запуск клиента** блокируется: Phase 2 готов + материалы клиента + домен/хостинг

---

## Текущий статус

**Сейчас**: Этап 1 (подготовка) завершён частично → переход к Этапу 2 (регистрации) + Этапу 3 (scaffold) параллельно.

**Следующее действие**: создать scaffold (Этап 3) + начать Meta App регистрацию.
