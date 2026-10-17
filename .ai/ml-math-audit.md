# ML / Math Audit — Marketing AI Platform
**Документ для математического аудита логических ошибок**

> Каждый файл содержит: назначение, математическую логику, входные/выходные данные, взаимодействие с другими файлами, потенциальные риски. Полный исходный код — после описания.

---

## СЕКЦИЯ 1 — Thompson Sampling и Байесовские эксперименты

---

### 1.1 `ml-service/app/services/experiment_service.py`

**Назначение:** Управление жизненным циклом A/B-экспериментов и принятие решения о завершении/паузе.

**Математическая логика:**
- **ESS (Effective Sample Size)** = Σ(α + β − 2) по всем рукавам. Вычитание 2 удаляет плоский приор Beta(1,1).
- **P(best)** — Монте-Карло симуляция: n=5000 семплов из Beta(α_i, β_i) для каждого рукава; доля итераций, где рукав i выиграл у всех остальных.
- **Авто-завершение:** P(best) > 0.95 И ESS ≥ 200.
- **Авто-пауза:** P(хуже контроля) > 0.95 И ESS ≥ 50. Контрольный рукав НИКОГДА не паузируется.
- **Информированные приоры при перезапуске:** победитель → Beta(5,1), проигравший → Beta(1,5), сомнительный → Beta(2,2).
- **Деградация к приору:** α = 1 + 0.99·(α−1); β = 1 + 0.99·(β−1) (не наивное умножение α×0.99).

**Входные данные:** список рукавов с (α, β, variant_id, is_control), min_ess, auto_stop_p.
**Выходные данные:** {should_conclude, winner_variant_id, reason}; список рукавов для паузы.

**Взаимодействие:** вызывается из `ExperimentService.java` (Spring Boot). Единственный писатель thompson_arms.

**Потенциальные риски:**
- При n=5000 оценка P(best) имеет стандартную ошибку ~0.7% → при граничном P=0.95 можно ошибиться. Рекомендуется n=10000+.
- ESS = Σ(α+β−2) корректен только при Beta(1,1) приоре. Если приоры информированы (Beta(5,1)), ESS занижается.

---

### 1.2 `ml-service/app/services/thompson_service.py`

**Назначение:** Полный асинхронный Thompson Sampling — выбор рукава, логирование решений, применение наград.

**Математическая логика:**
- **Семплинг:** sample(α, β) из Beta-распределения для каждого рукава; выбирается максимум.
- **Пропенсити (propensity):** N=200 Монте-Карло семплов; доля, в которой выбранный рукав победил = P(arm selected).
- **Reward Map:** QUALITY_GATE_PASS → +1.0 к α (победа); только бинарные инкременты. Дробные дельты запрещены.
- **Churn penalty:** cap=30% от (α+β). Штраф не может уменьшить α+β более чем на 30%.
- **Benchmark:** P(treatment > holdout) via Монте-Карло N=10000.
- **Redis кеш:** TTL 15 минут на состояние рукавов.

**Входные данные:** tenant_id, variant_id, reward (1.0), event_type.
**Выходные данные:** выбранный variant_id, propensity, решение залогировано в decision_log.

**Взаимодействие:** вызывается FastAPI эндпоинтами `/thompson/select`, `/thompson/reward`; результат используется в `funnel_path_service.py`.

**Потенциальные риски:**
- N=200 для пропенсити — низкое. Грубая оценка вероятности выбора; для OPE нужна точность выше.
- Кеш 15 мин: в быстрых экспериментах (много трафика) posterior устаревает.

---

### 1.3 `ml-service/app/services/followup_service.py`

**Назначение:** Thompson Sampling для выбора рукава follow-up последовательности после звонка.

**Математическая логика:**
- **Контекстуализация:** domestic/intl × high/low ticket → 4 контекстных сегмента.
- **Exploration rate:** 0.15 (15% вероятность случайного выбора вместо Thompson).
- **Авто-пауза:** ESS = α+β−2; n_simulations=2000; P(хуже) > 0.95 при ESS ≥ 50.
- **Show-rate posterior:** Beta posterior на данных o show/no-show; 90% CI via n=2000 семплов.

**Входные данные:** tenant_id, context (domestic/intl, high/low ticket), список рукавов.
**Выходные данные:** выбранный рукав follow-up, флаг паузы.

**Взаимодействие:** вызывается `FollowupController.java`; данные о show-rate берутся из noshow_scores.

**Потенциальные риски:**
- ESS = α+β−2 при информированных приорах (Beta(5,1)) даст ESS=4 сразу при создании → риск преждевременной паузы при ESS ≥ 50 фактически нейтрализован, но формально некорректно.

---

### 1.4 `ml-service/app/services/funnel_path_service.py`

**Назначение:** Thompson Sampling для выбора пути воронки (Funnel Path A/B).

**Математическая логика:**
- **TIERED override:** если lead_score ≥ high_tier_threshold → принудительный путь B независимо от Thompson.
- **Gate B:** MATERIAL_VIEW + DATAROOM_OPEN/VIDEO_75 — дополнительные условия для входа в путь B.
- **Метрика качества:** held_calls / 1000 LPV с 80% доверительным интервалом; z=1.282.

**Входные данные:** tenant_id, session_id, lead_score.
**Выходные данные:** выбранный funnel_path_id, вариант лендинга.

**Взаимодействие:** вызывается из `FunnelPathController.java`; использует thompson_arms для пути.

---

## СЕКЦИЯ 2 — Предиктивные модели

---

### 2.1 `ml-service/app/services/noshow_service.py`

**Назначение:** Предсказание вероятности no-show для каждого бронирования.

**Математическая логика:**
- **Rule-based v1:** базовый rate = 0.30; корректировки по признакам:
  - booking_distance_h > 72ч → +0.10
  - timezone: EST/PST/CST → +0; другие → +0.05
  - ticket_bucket: LT25K → +0.15; GT250K → −0.10
  - accredited = False → +0.10
  - device = mobile → +0.05
  - is_international → +0.08
  - Сумма, ограничение [0.05, 0.95]
- **Tier:** HIGH ≥ 0.5, MED ≥ 0.3, LOW < 0.3.
- **Заполнение слота:** P(никто не придёт) = Π p_noshow по всем бронированиям в слоте.

**Входные данные:** детали бронирования (время, тикет, аккредитация, устройство, таймзона).
**Выходные данные:** {risk_score, risk_tier} → сохраняется в noshow_scores.

**Взаимодействие:** результат используется `lead_value_service.py` (p_show = 1 − risk_score), `followup_service.py`, `BookingWindowService.java`.

**Потенциальные риски:**
- Произвольные пороги (+0.10, +0.05 и т.д.) без эмпирической калибровки. До накопления ≥200 реализованных лидов — только экспертная оценка.
- P(никто не придёт) = произведение — предполагает независимость бронирований в слоте, что может быть нереалистично (групповые бронирования).

---

### 2.2 `ml-service/app/services/lead_value_service.py`

**Назначение:** Предсказание ожидаемой ценности лида (Predicted Lead Value, PLV).

**Математическая логика:**
```
PLV = p_show × p_verified|show × p_invest|verified × E[ticket|bucket]
```
- **p_show** = 1 − noshow_risk_score из noshow_scores
- **p_invest** = seed 0.21 (до 200 реализованных лидов)
- **p_verified** = seed 0.65 (до 200 реализованных лидов)
- **E[ticket|bucket]:** LT25K=0.3, 25_50K=0.6, 50_100K=1.0 (base=50K), 100_250K=1.8, GT250K=3.0 × k_ticket
- **OLS калибровка:** на ≥200 реализованных лидов; slope принимается если ∈[0.8, 1.2]. При отклонении slope остаётся 1.0.

**Входные данные:** noshow_scores.risk_score, ticket_bucket, аккредитация.
**Выходные данные:** predicted_value_usd → сохраняется в lead_value_predictions.

**Взаимодействие:** вызывается `LeadValueService.java` на событие BOOKED; realised value записывается при INVEST_COMMITTED.

**Потенциальные риски:**
- Произведение четырёх вероятностей — ошибка в любом компоненте умножается. При p_show=0.7, p_verified=0.65, p_invest=0.21 → PLV = 0.70×0.65×0.21×k ≈ 9.5% от k_ticket.
- OLS slope [0.8,1.2] — хороший диапазон, но если истинный slope = 1.5 (систематическое занижение), калибровка будет отклонена и модель выдаёт неверные значения бесконечно.

---

### 2.3 `ml-service/app/services/reward_service.py`

**Назначение:** Вычисление наград за прохождение стадий воронки + Show-Rate Corrector.

**Математическая логика (основная часть):**
- Thompson increment = 1.0 ТОЛЬКО для quality gate stage (CALL_HELD). Дробные дельты запрещены.
- Funnel progress score = deepest_ordinal / max_ordinal ∈ [0,1] — для аналитики, НЕ для Thompson.
- Quality rate posterior: Beta(quality_hits+1, noshow+1); 80% CI via z=1.282.

**Show-Rate Corrector (vision §19.10):**
```
post_α = prior_α + held_count
post_β = prior_β + (booked - held)
post_mean = post_α / (post_α + post_β)
blended = (1 - 0.3) × post_mean + 0.3 × prior_mean
multiplier = blended / prior_mean ∈ [0.5, 1.5]
```
- prior_α=3.0, prior_β=2.0 → prior_mean = 0.6 (60% ожидаемый show rate)
- multiplier < 1.0 → плохой show rate → ужесточение L1/L2 gate порогов (threshold / multiplier)

**Взаимодействие:** `ShowRateCorrectorScheduler.java` запускает еженедельно (пн 06:00); результат пишется в tenants.settings.show_rate_multiplier.

**Потенциальные риски:**
- Shrinkage = 0.3 фиксирован. При малой выборке (booked_count < 10) posterior почти равен приору — корректор не чувствителен к реальным данным.
- apply_show_rate_to_gates: threshold / multiplier при multiplier=0.5 удваивает порог. Если L2=0.9 → после коррекции L2=1.8, что никогда не достигается → все лиды блокируются. Граница [0.5, 1.5] ограничивает умножение, но граница порогов [0,1] не проверяется.

---

### 2.4 `ml-service/app/services/propensity_service.py`

**Назначение:** Оценка P(конверсии) для сессии — правило-базированный фолбек + Gradient Boosting.

**Математическая логика:**
- **Rule-based:** base=0.05; +0.50 если form_submitted; +0.20 если form_started; +0.10 repeat visit; +0.10 cta_time>5s; −0.05 short session; ограничение [0,1].
- **Gradient Boosting:** sklearn Pipeline(StandardScaler, GradientBoostingClassifier); n_estimators=200, max_depth=4, learning_rate=0.05, random_state=42.
- **Uplift:** p_convert_personalised − p_convert_control ∈ [−1, 1].
- **Objection:** определяется по порогам времени на блоках (price>20s, proof>25s, session<15s).

**Входные данные:** feature vector из cluster_service (11 признаков) + cluster_id.
**Выходные данные:** {p_convert, main_objection}.

**Взаимодействие:** использует признаки из `cluster_service.py`; вызывается `decide_service.py`.

**Потенциальные риски:**
- Model deserialisation via joblib из базы данных — риск безопасности (pickle injection) при ненадёжном источнике.
- Минимум 30 сессий для обучения — недостаточно для GBM. Рекомендуется ≥1000.

---

### 2.5 `ml-service/app/services/burnout_survival_service.py`

**Назначение:** Предсказание выгорания (burnout) креатива через модель выживаемости Cox PH + Weibull.

**Математическая логика:**
- **Cox Proportional Hazard:** h(t) = h0(t) × exp(β₁×log_frequency + β₂×log_initial_roas)
  - DEFAULT_BETAS: log_frequency=0.8, log_initial_roas=−0.5
- **Weibull функция выживаемости:** S(t) = exp(−(t/λ_eff)^k), scale=14, shape=1.5
  - λ_eff = scale / exp(β×X)^(1/k)
- **Бинарный поиск:** день t*, где S(t*) < 0.5 (медиана выживаемости) → burnout_day.
- **Фолбек для N<30:** эвристика: 90/(1 + frequency) дней.

**Входные данные:** frequency (частота показа), initial_roas.
**Выходные данные:** {burnout_day, survival_at_30d, hazard_ratio}.

**Взаимодействие:** используется `BurnoutController.java`; результат влияет на решение о ротации креативов.

**Потенциальные риски:**
- DEFAULT_BETAS — эмпирически не откалиброваны под инвестиционный нейш (получены из общих данных).
- При log_initial_roas=0 (ROAS не известен) hazard ratio не меняется, но это корректно только если ROAS не коррелирует с выгоранием.
- Weibull scale=14 дней — агрессивно короткий базовый срок жизни для инвестиционного контента.

---

## СЕКЦИЯ 3 — Атрибуция

---

### 3.1 `ml-service/app/services/attribution_service.py`

**Назначение:** Многомодельная атрибуция конверсий по каналам.

**Математическая логика:**

**Markov Attribution:**
- Строится матрица переходов P(состояние j | состояние i) по всем путям.
- Removal effects: для каждого канала к убирается строка/столбец → вычисляется P(конверсии) без k.
- Вес канала k = (P_полная − P_без_k) / Σ_j(P_полная − P_без_j).

**Shapley Value (Monte Carlo):**
- n=200 случайных коалиций, seed=42.
- Для каждой коалиции: marginal contribution канала k = v(S∪{k}) − v(S).
- Вес k = среднее marginal contribution по всем коалициям.
- n=200 — точность ~±5% для 5 каналов.

**Time-Decay:**
- Вес касания в момент t_i: w_i = 2^(−(T−t_i)/half_life), half_life=7 дней.
- Нормализованные веса → атрибуция.

**Data-Driven:**
- Предобученные channel_weights из external source (фолбек = равномерное распределение).

**Last-Touch, Linear:** стандартные алгоритмы.

**Входные данные:** список touchpoints с channel_id и timestamp.
**Выходные данные:** {channel_id: attribution_value} для каждой модели.

**Потенциальные риски:**
- n=200 для Shapley — очень мало. При 6+ каналах возможны высокие отклонения. Рекомендуется n≥1000.
- Markov предполагает стационарность цепи Маркова — нарушается при сезонности.
- seed=42 фиксирован → один и тот же порядок коалиций каждый раз. Следует проверить на чувствительность к seed.

---

## СЕКЦИЯ 4 — Статистическая аналитика

---

### 4.1 `ml-service/app/services/calibration_service.py`

**Назначение:** ECE (Expected Calibration Error) для оценки калиброванности вероятностных моделей.

**Математическая логика:**
```
ECE = Σ_{b=1}^{B} (n_b / N) × |mean_pred_b − mean_actual_b|
```
- B=10 бинов; n_b — количество семплов в бине b; N — всего.
- gap < 0.05 → модель считается калиброванной.

**Взаимодействие:** `model_quality_service.py` использует аналогичную формулу для оценки Thompson моделей.

**Потенциальные риски:**
- ECE чувствителен к выбору B. B=10 стандарт, но при малой выборке (<200) ряд бинов будет пустым.

---

### 4.2 `ml-service/app/services/ope_service.py`

**Назначение:** Off-Policy Evaluation для валидации политики перед деплоем с реальным бюджетом.

**Математическая логика:**
- **IPS:** Σ(w_i × r_i) / n; w_i = π_новая(a_i|x_i) / π_старая(a_i|x_i).
- **SNIPS (self-normalized):** Σ(w_i × r_i) / Σw_i — уменьшает дисперсию.
- **Doubly-Robust:** IPS − w×mean_r + mean_r — комбинирует direct model и IPS.
- **ESS:** (Σw)² / Σw² — эффективный размер выборки.
- **Bootstrap CI:** n=500 бутстреп выборок; w_max=10.0 (кеппинг весов).
- **Условие PASS:** SNIPS ≥ 0.95 AND ESS ≥ 200.

**Входные данные:** decisions (action, propensity, reward) из decision_log.
**Выходные данные:** {ips, snips, dr, ess, ci_lower, ci_upper, pass}.

**Взаимодействие:** `OpeController.java`; propensity берётся из `thompson_service.py` (n=200 симуляций).

**Потенциальные риски:**
- w_max=10.0 — агрессивный кеппинг. При неравномерной политике (одни рукавы выбираются >90% времени) веса для редких рукавов обрезаются → IPS занижен.
- n=500 бутстрепов для CI — минимально достаточно. Рекомендуется n≥1000.
- Propensity из n=200 симуляций (thompson_service) — с погрешностью. Неточная propensity → смещённый IPS/SNIPS.

---

### 4.3 `ml-service/app/services/cb_bayesian_service.py`

**Назначение:** Байесовский circuit breaker для мониторинга CPA.

**Математическая логика:**
- **Beta posterior на CVR:** α = conversions + 1; β = clicks − conversions + 1.
- **Семплинг:** n Монте-Карло семплов из Beta(α, β).
- **CPA из семпла:** cpa_sample = spend_per_click / cvr_sample.
- **P(CPA > 2×target):** доля семплов, где cpa_sample > threshold.
- **Триггер:** P > 0.90.

**Взаимодействие:** `CbBayesianController.java`; вызывается автоматически при обновлении рекламных метрик.

**Потенциальные риски:**
- CPA = spend_per_click / CVR_sample — только если CVR уже включает все кликнувших. Если clicks ≠ покупатели/лиды, знаменатель некорректен.
- Порог 2×target фиксирован. Для инвестиционного нейша с высоким ticket нормальное отклонение CPA может быть >2×.

---

### 4.4 `ml-service/app/services/glm_interactions_service.py`

**Назначение:** BIC-based отбор взаимодействий в GLM модели.

**Математическая логика:**
- ΔBIC = BIC_restricted − BIC_full
- BF ≈ exp(0.5 × ΔBIC)  (приближение Байесовского фактора через BIC)
- BF > 3 → INCLUDE взаимодействие
- BF < 1/3 → EXCLUDE
- 1/3 ≤ BF ≤ 3 → INCONCLUSIVE

**Взаимодействие:** `GlmController.java`; используется для оценки взаимодействий atom×segment.

**Потенциальные риски:**
- BIC-based BF — это приближение, корректное только асимптотически (большая выборка). При n<100 BF занижен.
- exp(0.5 × ΔBIC) может overflow при большом ΔBIC. Нужна проверка на NaN/Inf.

---

### 4.5 `ml-service/app/services/diagnostics_service.py`

**Назначение:** Диагностика симптомов с коррекцией множественного тестирования (Benjamini-Hochberg).

**Математическая логика:**
- **Z-score:** z = (observed − baseline) / (baseline_std / √n).
- **P-value:** аппроксимация A&S полиномиального ряда для Φ(z).
- **BH процедура:** сортировка p-значений; порог для ранга k: FDR × k / n; первое нарушение — все выше не значимы.
- **FDR = 0.10** (10% ожидаемая доля ложноположительных).
- **MIN_N = 100** для подавления симптомов с малой выборкой.

**Взаимодействие:** `DiagnosticsController.java`; симптомы передаются в `signals_service.py`.

**Потенциальные риски:**
- BH процедура предполагает независимость тестов. CTR и CVR на одних данных — коррелированы → BH консервативнее, чем нужно.
- A&S приближение для p-value менее точно при |z| > 3.5.

---

### 4.6 `ml-service/app/services/quality_service.py`

**Назначение:** Вычисление quality score сессии + детекция кликбейта + CVR collapse.

**Математическая логика:**
- **Quality score:** weighted sum с равными весами 0.25 по 4 метрикам: match_score, non_bounce, scroll_depth, form_completion.
- **Кликбейт:** P(CTR_creative > CTR_anchor) > 0.9 AND (P(CVR_creative < CVR_anchor) > 0.9 OR P(bounce_creative > bounce_anchor) > 0.9) — нормальное приближение для пропорций.
- **CVR collapse:** двухвыборочный z-тест; threshold = −30% относительного падения; p < 0.05.
- **P(A>B):** нормальное приближение: z = (rate_A − rate_B) / √(se_A² + se_B²); Φ(z).

**Потенциальные риски:**
- Нормальное приближение для пропорций некорректно при n < 30 или rate < 0.05.
- Quality score не добавляется в alpha/beta Thompson — это правильно по архитектуре, но должно быть явно задокументировано для ревьюеров.

---

### 4.7 `ml-service/app/services/red_flags_service.py`

**Назначение:** Раннее выключение лендингов с плохим bounce rate (early kill).

**Математическая логика:**
- **Beta posterior:** Beta(bounced + 1, total − bounced + 1).
- **P(bounce > threshold):** Монте-Карло 5000 семплов via Gamma-based Beta sampler (random.Random(0)).
- **Kill condition:** P > 0.90.
- **Порог bounce threshold:** niche_mean + 1.5 × niche_sigma (из niche benchmark); фолбек = 0.75.
- **MIN_VISITS = 50** перед любой оценкой.
- Применяется ТОЛЬКО к лендингам, НЕ к Meta-креативам.

**Взаимодействие:** `RedFlagController.java`; threshold берётся из `niche_benchmark_service.py`.

**Потенциальные риски:**
- random.Random(0) — фиксированный seed → одинаковая последовательность для каждого вызова. Не критично для оценки вероятности, но детерминирует любые отладочные запросы.
- При MIN_VISITS=50 и conversion rate 0.03 ожидаем лишь ~1.5 конверсии → порог 50 посещений достаточен для bounce (которых много), но не для конверсий.

---

### 4.8 `ml-service/app/services/model_quality_service.py`

**Назначение:** Оценка качества ML-моделей (log-loss, Brier score, ECE).

**Математическая логика:**
- **Log-loss:** −(1/n) Σ [y×log(p) + (1−y)×log(1−p)]; p клипируется к [ε, 1−ε] при ε=1e-7.
- **Brier score:** (1/n) Σ (p_i − y_i)².
- **ECE:** см. calibration_service.py (10 бинов).
- **Пороги GOOD:** log-loss<0.40, Brier<0.15, ECE<0.10.
- **Пороги DEGRADED:** log-loss>0.65, Brier>0.25.
- **RMSE:** для регрессионных моделей (MMM, GLM): √(1/n Σ(y−ŷ)²).
- Статус GOOD/ACCEPTABLE/DEGRADED по пересечению порогов.

**Взаимодействие:** используется для мониторинга Thompson и Propensity моделей.

**Потенциальные риски:**
- MIN_SAMPLE_FOR_QUALITY = 50 — очень мало для надёжной log-loss оценки.

---

### 4.9 `ml-service/app/services/segment_re_service.py`

**Назначение:** Байесовский частичный пулинг эффектов сегментов (аудитория × атом).

**Математическая логика:**
- **Shrinkage weight:** w = n / (n + 1/τ²), TAU_SQUARED=4.0.
- **Posterior:** estimate = w × local_mean + (1−w) × global_mean.
- **SE:** √(estimate × (1−estimate) / n).
- **80% CI:** z=1.282.
- При n < 30 → полный shrinkage к global_mean.

**Взаимодействие:** `SegmentEffectController.java`; используется для ранжирования атомов по сегментам.

**Потенциальные риски:**
- TAU_SQUARED=4.0 означает очень слабый shrinkage (при n≥10 w→1.0). С τ²=4, при n=10: w=10/(10+0.25)=0.976 → почти нет shrinkage. Эффективный shrinkage только при n<5.
- MIN_N=30 для shrinkage к prior противоречит: при n=10 shrinkage w=0.97 (почти нет), а при n=30 срабатывает полный режим.

---

## СЕКЦИЯ 5 — ML и сигналы

---

### 5.1 `ml-service/app/services/cluster_service.py`

**Назначение:** Поведенческая кластеризация сессий (K-means) + персонализация порядка блоков.

**Математическая логика:**
- **Признаки (11):** session_duration, scroll_depth, scroll_velocity, block_price/proof/cta_time, form_started/submitted, is_mobile, is_repeat_visit, event_count.
- **K-means:** sklearn, n_clusters=7, random_state=42, n_init=10. StandardScaler перед кластеризацией.
- **Online assignment:** Евклидово расстояние, нормализованное по |centroid| (относительные отклонения).
- **Rule-based label:** иерархия правил (casual_bounce → form_abandoner → engaged_returner → price_seeker → proof_seeker → ready_to_buy → researcher).
- **IPS weight:** w = 1/propensity; clip [0.1, 10.0]; ESS = (Σw)²/Σw².
- **Блок-ордер:** CLUSTER_BLOCK_RULES — 7 паттернов ре-упорядочивания блоков по кластеру.

**Взаимодействие:** признаки передаются в `propensity_service.py`; cluster_id → `decide_service.py`.

**Потенциальные риски:**
- Евклидово расстояние с нормализацией по |centroid| нестандартно и не эквивалентно StandardScaler-based distance. Несогласованность между fit (стандартизация) и online assign (нормализация по centroid).
- n_clusters=7 фиксировано. Без Elbow/Silhouette анализа — произвольный выбор.
- MIN для K-means: требуется минимум 7 сессий, что нереалистично мало.

---

### 5.2 `ml-service/app/services/hierarchy_prior_service.py`

**Назначение:** Трёхуровневая иерархия Байесовских приоров: universal → niche → client.

**Математическая логика:**
- **Universal priors:** CTR~N(0.02, 0.01), CVR~N(0.03, 0.02), bounce~N(0.55, 0.15).
- **Niche prior:** StudentT(ν=4, μ_niche, σ_niche) — тяжёлые хвосты для устойчивости к выбросам.
  - μ_niche = μ_universal + effect_size × σ_universal
  - σ_niche = max(CI_half/1.96, σ_universal/2)
- **Client posterior (Normal-Normal conjugate):**
  - τ_prior = 1/σ_prior², τ_obs = n/σ_obs²
  - μ_post = (τ_prior×μ_prior + τ_obs×μ_obs) / (τ_prior + τ_obs)
  - σ_post = 1/√(τ_prior + τ_obs)
- При n < 30 → niche prior (без обновления).

**Взаимодействие:** используется для инициализации приоров в diagnostics, benchmark, GLM.

**Потенциальные риски:**
- Normal-Normal conjugate корректен только если likelihood — нормальная. Для CTR/CVR (бинарные пропорции) это приближение.
- StudentT niche prior → нормальный client posterior — смешивание семейств распределений. Формально некорректно, но на практике приемлемо как приближение.

---

### 5.3 `ml-service/app/services/signals_service.py`

**Назначение:** Классификация аномалий (MARKET/CREATIVE/TECHNICAL/FRAUD/NONE) и маршрутизация внешних сигналов.

**Математическая логика:**
- **Приоритет классификации:** TECHNICAL > FRAUD > MARKET > CREATIVE > NONE.
- **CPM z-score:** z = (CPM_current − μ_14d) / σ_14d; порог = 2.5.
- **Fraud threshold:** 15% подозрительных сессий.
- **Budget multiplier (DEMAND воронки):** drought_severe=1.6, drought_moderate=1.3, heat_stress=1.2; +10% за каждый urgent calendar event ≤7 дней.
- NARRATIVE воронки: бюджет не меняется (multiplier=1.0).

**Взаимодействие:** `AnchorController.java` передаёт anchor_verdict; результат используется `DiagnosticsController.java`.

**Потенциальные риски:**
- Только 5 точек минимально для CPM z-score. При 5 точках дисперсия крайне нестабильна.

---

### 5.4 `ml-service/app/services/burnout_service.py`

**Назначение:** Быстрое (без выживаемости) вычисление burnout score креатива.

**Математическая логика:**
```
score = 0.6 × ctr_drop_pct + 0.4 × frequency_penalty
frequency_penalty = clip((frequency − 1.5) / 6.5, [0, 1])
```
- critical: score > 0.7; warning: score > 0.35; none: иначе.
- **Estimate days remaining:** 0.35 / ctr_drop_rate_per_day; clip [1, 90].

**Взаимодействие:** вызывается из `burnout_survival_service.py` как fallback при N<30.

**Потенциальные риски:**
- frequency_penalty: базовая frequency=1.5 — если реклама показывается 2 раза в день, penalty уже 0.077. Это агрессивно для brand awareness кампаний.
- estimate_days_remaining: линейная экстраполяция CTR drop rate. CTR обычно падает нелинейно.

---

### 5.5 `ml-service/app/services/saturation_service.py`

**Назначение:** Детекция насыщения аудитории vs. усталости от креатива vs. сдвига рынка.

**Математическая логика:**
```
eff_score = min(1, (1 − reach_efficiency) / baseline_efficiency)
freq_score = min(1, (frequency − frequency_prev) / 5.0)
score = 0.5 × eff_score + 0.5 × freq_score
```
- critical: score > 0.65; warning: score > 0.30.
- **Диагностика:** creative_fatigue если CTR упал без падения reach; audience_saturation если reach упал + high_freq; market_shift если reach упал без high_freq.

**Взаимодействие:** `SaturationController.java`; входные данные — метрики из Meta API.

**Потенциальные риски:**
- freq_score нормализован на 5.0 (подъём частоты). При исторически более высоком подъёме score будет занижен.
- Разделение на усталость/насыщение/сдвиг рынка — пороги CTR<−15% и reach<0.70 произвольны.

---

### 5.6 `ml-service/app/services/entropy_service.py`

**Назначение:** Оценка разнообразия контента через косинусное подобие эмбеддингов.

**Математическая логика:**
```
entropy = 1 − mean_cosine_similarity(всех пар хуков)
```
- Порог: entropy < 0.3 при ≥5 тенантах → флаг LOW_DIVERSITY.
- AGGREGATE_FLOOR = 5 тенантов (чтобы не раскрывать данные одного тенанта).

**Взаимодействие:** `EntropyController.java`; эмбеддинги генерируются llm_client.

**Потенциальные риски:**
- 1 − mean_cosine_similarity не является информационной энтропией. Это мера разнообразия, но назвать "entropy" может вводить в заблуждение математиков.
- При 5 тенантах mean similarity из 10 пар — достаточно? Математически: нет для стабильной оценки.

---

### 5.7 `ml-service/app/services/niche_benchmark_service.py`

**Назначение:** Первичные бенчмарки по нише (p50, p75 метрик).

**Математическая логика:**
- AGGREGATE_FLOOR = 5 тенантов на ячейку (ниша × размещение × устройство).
- Процентили: numpy percentile по реализованным метрикам.
- Сравнение: above (>p50+0.02), below (<p50−0.02), at_median.
- ТОЛЬКО первичные события (first-party) — Meta API данные запрещены до юридической проверки.

**Взаимодействие:** `NicheBenchmarkController.java`; threshold для red_flags берётся отсюда; niche prior → `hierarchy_prior_service.py`.

---

### 5.8 `ml-service/app/services/evolution_service.py`

**Назначение:** Эволюционная генерация креативов (crossover + mutation + MMR diversification).

**Математическая логика:**
- **Crossover:** 50/50 — для каждого типа атома случайно берётся от parent_a или parent_b.
- **Mutation:** каждый атом независимо заменяется с P=0.15 на случайный из pool того же типа.
- **Coherence check:** cosine(hook_emb, body_emb) ≥ 0.65.
- **Graveyard similarity:** cosine(candidate, failed) ≥ 0.85 → флаг (по аудитории).
- **GLM posterior ranking:** семплы из N(μ_posterior, σ_posterior); ranking по E[sample].
- **MMR diversification:** λ=0.3; score = (1−λ)×relevance + λ×(1−max_sim_to_selected).

**Взаимодействие:** `EvolveController.java`; atom_pool берётся из approved_atoms; graveyard из `GraveyardController.java`.

**Потенциальные риски:**
- При одном parent_a и parent_b (len(ranked)==1) crossover вернёт копию одного родителя → все кандидаты идентичны до мутации.
- MMR: если у кандидатов нет поля "embedding", _cosine возвращает 0 (деление на 0 обработано), но MMR score будет некорректным.

---

### 5.9 `ml-service/app/services/budget_curve_service.py`

**Назначение:** Монте-Карло предсказание CPA corridor по уровням бюджета.

**Математическая логика:**
```
cvr_sample ~ Beta(α, β)
sessions = budget × sessions_per_dollar
conversions = cvr_sample × sessions
CPA = budget / conversions  (∞ если conversions=0)
```
- p10, p50, p90 из distribution of CPA, исключая Inf.
- **Diminishing returns:** первый бюджетный уровень, где CPA_p50 растёт >20% от предыдущего.
- n_mc=2000 per budget point; rng_seed=i для воспроизводимости.

**Взаимодействие:** `PredictiveController.java`; α, β берутся из Thompson arms для основного варианта.

**Потенциальные риски:**
- sessions = budget × sessions_per_dollar предполагает линейную зависимость трафика от бюджета — некорректно при насыщении аудитории. Нужна Hill-кривая.
- Исключение Inf из CPA — если 90%+ семплов имеют CVR≈0, p50 тоже Inf → вернёт None. Нет обработки этого случая в caller.

---

## СЕКЦИЯ 6 — Compliance Math

---

### 6.1 `ml-service/app/services/compliance_service.py`

**Назначение:** Проверка текстов на соответствие Meta Advertising Policies и правилам SEC.

**Математическая логика:**
- Regex-based правила: каждое правило имеет pattern, severity (FAIL/WARN), suggestion.
- BIC-like приоритизация: FAIL > WARN > PASS.
- **META_PERSONAL_ATTR:** 6 паттернов (PA001–PA005) — описание предложения, а не зрителя.
- Trademark: точное совпадение (case-insensitive).
- LLM проверка (claude-haiku-5-5) для META_POLICY и CLAIMS — асинхронная, с фолбеком на WARN при ошибке.

**Взаимодействие:** `ModerationController.java`; rulebases хранятся в compliance_rulebases; LLM вызывается через `llm_client.py`.

**Потенциальные риски:**
- Regex для юридических правил — ненадёжен. Paraphrase нарушений не детектируется.
- LLM-based проверка: модель меняется со временем → результаты могут измениться при обновлении модели.
- При llm_client=None возвращается WARN, а не FAIL → систематический downgrade severity.

---

### 6.2 `ml-service/app/services/moderation_service.py`

**Назначение:** Compliance by Construction + META_REVIEWER persona score.

**Математическая логика:**
- **Сборка из одобренных фраз:** только approved_phrases → если совпадение не найдено → new_phrases (требует юридического ревью).
- **Coverage:** 1 − (len(new_phrases) / max(total_chars, 1) × 10) — эвристика, не формула покрытия.
- **P(rejection):** `p_reject = 1 − (1−0.85)^fail_count × (1−0.20)^warn_count`
  - Предполагает независимость правил; каждый FAIL добавляет 85% вероятность отклонения.
  - ADVISORY ONLY — никогда не блокирует запуск.

**Взаимодействие:** `ModerationController.java`; persona META_REVIEWER сеется при инициализации.

**Потенциальные риски:**
- P(rejection) formula предполагает независимые нарушения — нереалистично. Два связанных FAIL считаются как 1−0.15² = 0.977, но это тот же контент.
- coverage = 1 − (len(new_phrases)/chars × 10) — делитель 10 произвольный.

---

### 6.3 `ml-service/app/services/geo_lift_service.py`

**Назначение:** Синтетический контроль для измерения инкрементального лифта по географии.

**Математическая логика:**
```
scale = mean(test_pre) / mean(control_pre)
synthetic_post = scale × mean(control_post)
lift = mean(test_post) − synthetic_post
lift_pct = lift / synthetic_post
```
- **Bootstrap CI (80%):** n=5000 бутстреп по временным рядам; α = (1−0.80)/2 = 0.10.
- **P(lift > 0):** доля bootstrap итераций с lift > 0.
- seed=42, MC_SAMPLES=5000.
- ТОЛЬКО на клиентских данных продаж, НЕ pixel данных (vision §17.3).

**Взаимодействие:** `GeoLiftController.java`; данные о продажах по геозонам.

**Потенциальные риски:**
- Простой synthetic control через scale ratio — слабая модель. Классический DiD или SDID был бы более точным.
- Требует ≥2 control geos. При 2 control geos bootstrap крайне нестабилен.
- Bootstrap семплирует независимо pre и post — нарушает временну́ю зависимость внутри рядов.

---

## СЕКЦИЯ 7 — Agency & Calibration

---

### 7.1 `ml-service/app/services/agency_service.py`

**Назначение:** Агрегация KPI по тенантам агентства + попарное сравнение источников креативов.

**Математическая логика:**
- **Beta CI для источников:** α = held+1; β = (lpv−held)+1; mean = α/(α+β); SE = √(mean×(1−mean)/max(lpv,1)).
- **80% CI:** ±1.282 × SE.
- **P(A>B):** нормальное приближение: z = (mean_A − mean_B) / √(se_A²+se_B²); Φ(z) через math.erf.

**Взаимодействие:** `AgencyController.java`; данные из agency_kpi_snapshots.

**Потенциальные риски:**
- SE используется из CI (ci_80_upper − mean ≈ 1.282×SE) — восстановление SE из округлённого CI вносит ошибку округления.
- Нормальное приближение для P(A>B) некорректно при малых lpv или extreme hold rate.

---

### 7.2 `ml-service/app/services/archetype_service.py`

**Назначение:** Классификация инвесторов по архетипам (6 типов).

**Математическая логика:**
- Rule-based scoring: суммирование очков по признакам для каждого архетипа.
- DISQUALIFIED: +0.7 если не аккредитован, +0.3 если LT25K.
- COMMITTED_QUICK: +0.8 при COMMITTED outcome, +0.3 при fast form (<3h), +0.3 при high ticket.
- RESEARCHER: +0.4 при ≥3 material views, +0.3 dataroom, +0.2 slow form (>24h), +0.2 high touch.
- FENCE_SITTER: +0.4×matched_objections, +0.3 repeated touches, +0.2 HIGH noshow risk.
- HIGH_VALUE_QUIET: +0.6 при high ticket + 0 material views + ≤2 touches, +0.3 no objections + GT250K.
- confidence = min(1.0, best_score); UNKNOWN если best_score < 0.3.

**Взаимодействие:** `InvestorArchetypeController.java`; вызывается на CALL_HELD событие.

**Потенциальные риски:**
- Очки не нормированы между архетипами → DISQUALIFIED (max=1.0) vs HIGH_VALUE_QUIET (max=0.9) — близкие значения, но семантически разные.
- Возможно одновременное высокое score у DISQUALIFIED и COMMITTED_QUICK (non-accredited + committed call). DISQUALIFIED выиграет только если score 1.0 vs 0.8.

---

### 7.3 `ml-service/app/services/post_call_service.py`

**Назначение:** Маршрутизация follow-up рукава по итогам звонка + статистика возражений.

**Математическая логика:**
- OBJECTION_ARM_MAP: 10 возражений → рукав (детерминированный mapping).
- First match wins — первое найденное возражение в списке определяет рукав.
- Conversion rate по возражению: count_converted / count_total.
- Commit rate: COMMITTED / total (all outcomes).

**Взаимодействие:** `PostCallController.java`; objection_tags сохраняются в call_notes.

**Потенциальные риски:**
- First match wins — порядок тегов в objection_tags влияет на выбор рукава. Не документировано.

---

### 7.4 `ml-service/app/services/form_qualification_service.py`

**Назначение:** Квалификация лида по форме — lead score + валидация + калибровка коэффициентов.

**Математическая логика:**
- **Lead score:** k_accr × k_ticket / 3.0
  - k_accr: SELF_ACCREDITED=1.0, PENDING=0.6, NO=0.2, None=0.4
  - k_ticket: LT25K=0.3, 25_50K=0.6, 50_100K=1.0, 100_250K=1.8, GT250K=3.0
- **Нормировка /3.0** — максимальный score = (1.0 × 3.0)/3.0 = 1.0.
- **Калибровка:** медианный ratio метода (observed_conversion_rate / predicted_lead_score).
- Принимается если ratio ∈[0.5, 2.0].

**Взаимодействие:** `FormQualificationController.java`; score влияет на TIERED override в funnel_path_service.

**Потенциальные риски:**
- k_accr×k_ticket — мультипликативная комбинация. При NO accreditation (0.2) и GT250K (3.0): score=0.2, что кажется низким для высокобюджетного инвестора.
- Медианный ratio для калибровки нестабилен при малой выборке.

---

### 7.5 `ml-service/app/services/mmm_service.py`

**Назначение:** Media Mix Modeling — adstock + Hill saturation + оптимизация бюджета.

**Математическая логика:**
- **Geometric adstock:** adstock(t) = spend(t) + decay × adstock(t−1); decay ∈[0,1].
- **Hill saturation:** f(x) = x^s / (hs^s + x^s); s = shape, hs = half-saturation.
- **Greedy marginal allocation:** увеличиваем бюджет на Δ для канала с максимальным marginal return; итерируем до распределения всего бюджета.
- Оптимизация только для каналов без CBO (CBO excluded).

**Взаимодействие:** `MmmController.java`; данные о spend и conversions по каналам и периодам.

**Потенциальные риски:**
- Greedy allocation не гарантирует глобальный оптимум. При неконвексной Hill-кривой может застрять в локальном оптимуме.
- Greedy шаг Δ не определён явно в service — зависит от caller. Слишком большой Δ → грубая аппроксимация.
- Adstock decay фиксирован как входной параметр — не оптимизируется, хотя это ключевой гиперпараметр.

---

### 7.6 `ml-service/app/services/portfolio_service.py`

**Назначение:** Оптимизация портфеля рекламных кампаний по Sharpe ratio.

**Математическая логика:**
```
Sharpe = (mean_r − rf) × 365 / (std_r × √365)
```
- rf = risk-free rate = 0.
- **Mean-variance optimisation:** минимизация дисперсии при заданном Sharpe; с 5% floor для каждой кампании.
- CBO кампании полностью исключены.
- **РЕКОМЕНДАЦИЯ ТОЛЬКО** — не автоматическое распределение.

**Взаимодействие:** `PortfolioController.java`; входные данные — исторические daily returns по кампаниям.

**Потенциальные риски:**
- Sharpe × 365 предполагает daily компаундирование. При месячных данных нужно ×12 (не ×365). Нужно уточнить временной масштаб return.
- std_r из исторических данных может занижать хвостовой риск (fat tails в рекламе).

---

## СЕКЦИЯ 8 — Java Math (Spring Boot)

---

### 8.1 `platform-api/.../service/GateEvaluationService.java`

**Назначение:** Байесовские ворота (Gate 1/2/3) для ad sets — kill/advance решения.

**Математическая логика:**
- **Gate 1 (Hook):** ≥5000 impressions AND ≥2 дней → P(CTR_adset > CTR_group_mean) ≥ 10%.
- **Gate 2 (Click+Landing):** ≥300 clicks → P(CTR > group_mean) ≥ 10%.
- **Gate 3 (Conversion):** ≥50 conversions → P(CVR > anchor_CVR) ≥ 10%.
- **Kill threshold:** P < 0.10 → KILLED.

**P(θ1 > θ2) — Marsaglia-Tsang Beta sampler (MC=10000):**
```java
// Marsaglia & Tsang (2000): Gamma(shape) via accept-reject
// Beta(a,b) = Gamma(a) / (Gamma(a) + Gamma(b))
// При shape < 1: Gamma(a) = Gamma(a+1) × U^(1/a)
```
- d = shape − 1/3; c = 1/√(9d)
- Accept if: u < 1 − 0.0331 × x⁴ (быстрая проверка)
- Accept if: log(u) < 0.5x² + d(1 − v + log(v)) (точная проверка)

**Взаимодействие:** вызывается `GateScanScheduler.java` (каждый час); `GateController.java` (вручную). Пишет статус в meta_ad_sets.gate_status.

**Потенциальные риски:**
- `new Random()` без seed → каждый вызов даёт разные результаты. При граничных P=0.10 возможна нестабильность.
- Group mean включает сам ad set → slight self-comparison bias.
- KILL_THRESHOLD = 0.10 — это очень мягкий порог. P < 10% означает "почти наверняка хуже среднего".

---

### 8.2 `platform-api/.../service/ExperimentService.java`

**Назначение:** Жизненный цикл A/B экспериментов (DRAFT→CONCLUDED); делегация математики FastAPI.

**Математическая логика:**
- Spring Boot НЕ вычисляет α/β — только вызывает FastAPI `/decide/experiment/should-conclude` и `/decide/experiment/check-pause`.
- Параметры из БД: min_ess, auto_stop_p.
- Бизнес-правила: ≥2 варианта, ровно 1 контрол перед review.

**Взаимодействие:** `ExperimentMonitorScheduler.java` (регулярный мониторинг); `experiment_service.py` (математика).

---

### 8.3 `platform-api/.../service/RewardService.java`

**Назначение:** Фиксация награды за прохождение воронки; триггер Thompson reward в FastAPI.

**Математическая логика:**
- Spring Boot пишет в reward_events и quality_gate_log.
- При is_quality_stage = TRUE → POST FastAPI `/thompson/reward` с payload `{reward: 1.0}`.
- **CRITICAL INVARIANT:** FastAPI — единственный писатель thompson_arms.alpha/beta. Spring Boot только уведомляет.

**Взаимодействие:** `RewardController.java`; `thompson_service.py` применяет reward.

---

### 8.4 `platform-api/.../scheduler/PosteriorRecalcScheduler.java`

**Назначение:** Мониторинг свежести posterior — логирование вариантов с новыми conversion events.

**Математическая логика:**
- Нет математики — только запрос к БД.
- Находит варианты с новыми CONVERSION событиями за последние 15 минут.
- **НЕ пишет** α/β — только логирует. Обновление posterior происходит при следующем вызове FastAPI `/decide`.

---

## ИТОГ: МАТРИЦА РИСКОВ ДЛЯ АУДИТА

| Файл | Критический риск |
|------|-----------------|
| experiment_service.py | n=5000 Monte Carlo для P(best) — погрешность ~0.7% у границы 0.95 |
| thompson_service.py | n=200 propensity — грубая оценка для OPE |
| noshow_service.py | Все параметры экспертные, нет калибровки на данных |
| lead_value_service.py | OLS slope [0.8,1.2]: если истинный >1.2 — постоянное занижение PLV |
| reward_service.py | apply_show_rate_to_gates: скорр. threshold может превысить 1.0 |
| cluster_service.py | Несогласованность метрики расстояния (fit vs assign) |
| segment_re_service.py | TAU²=4 → почти нет shrinkage при n≥10 |
| hierarchy_prior_service.py | StudentT→Normal conjugate: смешивание семейств |
| attribution_service.py | Shapley n=200 коалиций — погрешность ±5% |
| geo_lift_service.py | Простой scale-ratio synthetic control; bootstrap нарушает время. зависимость |
| ope_service.py | w_max=10 обрезает редкие рукавы; propensity из n=200 |
| portfolio_service.py | Sharpe ×365: зависит от временнóго масштаба return |
| evolution_service.py | Single-parent crossover возвращает клоны |
| GateEvaluationService.java | new Random() без seed → нестабильность при граничных значениях |
| moderation_service.py | P(reject): независимость нарушений предполагается |
| cb_bayesian_service.py | CPA=spend/CVR: корректно только если clicks=total exposures |

---

*Документ создан 2026-10-17. Версия платформы: Phase 8 complete, Client #1 onboarded.*
