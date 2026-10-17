from fpdf import FPDF

class ResponsePDF(FPDF):
    def header(self):
        if self.page_no() > 1:
            self.set_font("DejaVu", "I", 8)
            self.set_text_color(128, 128, 128)
            self.cell(0, 10, "Marketing AI Platform — Ответы на возражения", align="R")
            self.ln(5)

    def footer(self):
        self.set_y(-15)
        self.set_font("DejaVu", "I", 8)
        self.set_text_color(128, 128, 128)
        self.cell(0, 10, f"{self.page_no()}/{{nb}}", align="C")

    def section_title(self, num, title):
        self.set_font("DejaVu", "B", 14)
        self.set_text_color(30, 60, 120)
        self.ln(6)
        self.multi_cell(0, 8, f"{num}. {title}")
        self.ln(2)

    def sub_header(self, text):
        self.set_font("DejaVu", "B", 11)
        self.set_text_color(40, 40, 40)
        self.multi_cell(0, 7, text)
        self.ln(1)

    def body_text(self, text):
        self.set_font("DejaVu", "", 10)
        self.set_text_color(50, 50, 50)
        self.multi_cell(0, 6, text)
        self.ln(2)

    def verdict_box(self, text, color="green"):
        if color == "green":
            r, g, b = 34, 139, 34
            bg_r, bg_g, bg_b = 240, 255, 240
        elif color == "orange":
            r, g, b = 200, 120, 0
            bg_r, bg_g, bg_b = 255, 248, 235
        else:
            r, g, b = 50, 50, 50
            bg_r, bg_g, bg_b = 245, 245, 245

        self.set_fill_color(bg_r, bg_g, bg_b)
        self.set_draw_color(r, g, b)
        self.set_font("DejaVu", "B", 10)
        self.set_text_color(r, g, b)
        x = self.get_x()
        y = self.get_y()
        self.rect(x, y, 180, 10, style="DF")
        self.set_xy(x + 3, y + 2)
        self.multi_cell(174, 6, text)
        self.ln(4)

    def bullet(self, text):
        self.set_font("DejaVu", "", 10)
        self.set_text_color(50, 50, 50)
        x = self.get_x()
        self.set_x(x + 5)
        self.cell(5, 6, chr(8226))
        self.multi_cell(165, 6, text)
        self.ln(1)

    def reference(self, text):
        self.set_font("DejaVu", "I", 9)
        self.set_text_color(100, 100, 100)
        self.set_x(self.get_x() + 8)
        self.multi_cell(162, 5, text)
        self.ln(1)

    def separator(self):
        self.ln(3)
        self.set_draw_color(200, 200, 200)
        self.line(15, self.get_y(), 195, self.get_y())
        self.ln(5)


pdf = ResponsePDF()
pdf.alias_nb_pages()

font_path = "C:/Windows/Fonts/DejaVuSans.ttf"
font_path_bold = "C:/Windows/Fonts/DejaVuSans-Bold.ttf"
font_path_italic = "C:/Windows/Fonts/DejaVuSans-Oblique.ttf"

try:
    pdf.add_font("DejaVu", "", font_path, uni=True)
    pdf.add_font("DejaVu", "B", font_path_bold, uni=True)
    pdf.add_font("DejaVu", "I", font_path_italic, uni=True)
except:
    font_path = "C:/Windows/Fonts/arial.ttf"
    font_path_bold = "C:/Windows/Fonts/arialbd.ttf"
    font_path_italic = "C:/Windows/Fonts/ariali.ttf"
    pdf.add_font("DejaVu", "", font_path, uni=True)
    pdf.add_font("DejaVu", "B", font_path_bold, uni=True)
    pdf.add_font("DejaVu", "I", font_path_italic, uni=True)

pdf.set_auto_page_break(auto=True, margin=20)

# === TITLE PAGE ===
pdf.add_page()
pdf.ln(50)
pdf.set_font("DejaVu", "B", 24)
pdf.set_text_color(30, 60, 120)
pdf.multi_cell(0, 12, "Marketing AI Platform", align="C")
pdf.ln(5)
pdf.set_font("DejaVu", "", 16)
pdf.set_text_color(80, 80, 80)
pdf.multi_cell(0, 10, "Ответы на возражения", align="C")
pdf.ln(20)
pdf.set_font("DejaVu", "", 11)
pdf.set_text_color(100, 100, 100)
pdf.multi_cell(0, 7, "Давид, привет!", align="C")
pdf.ln(2)
pdf.set_font("DejaVu", "", 10)
pdf.multi_cell(0, 6, (
    "Спасибо за детальный разбор и конструктивные замечания.\n"
    "Ниже — ответы по каждому пункту с конкретными ссылками\n"
    "на разделы полного документа (vision.md v1.8).\n\n"
    "Часть твоих замечаний уже подробно раскрыта в документе —\n"
    "вероятно, тебе попала неполная версия. Два пункта действительно\n"
    "выявили пробелы, которые мы уже добавили в архитектуру."
), align="C")

pdf.ln(30)
pdf.set_font("DejaVu", "", 10)
pdf.set_text_color(100, 100, 100)
pdf.multi_cell(0, 6, "Арам\n10 октября 2026", align="C")


# === PAGE 2+ : CONTENT ===
pdf.add_page()

# ============================================================
# 1. OMNICHANNEL
# ============================================================
pdf.section_title(1, "Мультиформатность и диверсификация каналов (Omnichannel)")

pdf.sub_header("Твоё возражение:")
pdf.body_text(
    "Весь фокус заточен на Meta. Зависимость от одной экосистемы — огромный риск. "
    "Нужна архитектурная готовность к переносу на TikTok, YouTube, Google Performance Max."
)

pdf.sub_header("Что уже есть в документе:")

pdf.bullet(
    "Раздел 1 (Миссия): «Первый канал — Meta. Архитектура с первого дня "
    "заточена на добавление других каналов.»"
)
pdf.bullet(
    "Раздел 23, Phase 7 — «Масштабирование каналов»: прямо прописаны Google Ads, "
    "TikTok, YouTube с единым графом решений и канальными ветками."
)
pdf.bullet(
    "Раздел 22 (Риски): зависимость от Meta учтена в таблице рисков."
)

pdf.sub_header("Почему начинаем с одного канала:")
pdf.body_text(
    "Пытаться сразу строить мультиканальность = не запуститься никогда. "
    "Creative DNA (атомы), Thompson Sampling и байесовская модель — канало-независимые. "
    "Канало-специфичны только: API-интеграция, правила структуры аккаунта, форматы видео. "
    "Это адаптеры поверх ядра, не переписывание системы."
)

pdf.verdict_box("ВЕРДИКТ: Возражение обоснованное, но уже полностью учтено в архитектуре.", "green")

pdf.separator()

# ============================================================
# 2. CREATIVE FATIGUE
# ============================================================
pdf.section_title(2, "Баннероумытие и баннеры-франкенштейны (Creative Fatigue)")

pdf.sub_header("Твоё возражение:")
pdf.body_text(
    "Если ИИ будет бесконечно комбинировать одинаковые успешные хуки, рынок получит "
    "тысячи однотипных роликов. Нужен механизм, заставляющий систему генерировать "
    "принципиально новые смысловые заходы."
)

pdf.sub_header("Что уже есть в документе (подробно):")

pdf.bullet(
    "Раздел 5.2 — ДВА потока генерации: «поток вариаций» (рекомбинация атомов) "
    "И «поток концепций» (принципиально новые идеи, другой угол, другая эмоция). "
    "Прямо сказано: «без которого система застрянет в локальном оптимуме»."
)
pdf.bullet(
    "Раздел 6.4 — ЧЕТЫРЕ механизма постоянного исследования: "
    "(1) природа Thompson Sampling (автоматический exploration); "
    "(2) фиксированные 10-20% бюджета на чистые эксперименты; "
    "(3) генератор с оптимистичным приором для новых вариантов; "
    "(4) бандит по атомам."
)
pdf.bullet(
    "Раздел 8.1 — прогноз выгорания креатива: система обнаруживает ранние сигналы "
    "(Frequency растёт, Hook Rate падает) и готовит замену ЗАРАНЕЕ."
)
pdf.bullet(
    "Раздел 8.2 — прогноз аудиторного насыщения (отдельно от creative fatigue)."
)
pdf.bullet(
    "Раздел 13 — «Кладбище провалов» (Negative Learning): каждый провал разбирается "
    "на атомы, новые креативы проверяются на похожесть с кладбищем."
)
pdf.bullet(
    "Раздел 6.4 (v1.8) — Universal Model фокусирует exploration на реально неизвестном, "
    "а не на переоткрытии уже известных провалов."
)

pdf.verdict_box("ВЕРДИКТ: Возражение полностью закрыто в разделах 5.2, 6.4, 8.1, 8.2, 13.", "green")

pdf.separator()

# ============================================================
# 3. COMPLIANCE
# ============================================================
pdf.section_title(3, "Юридический модуль и Compliance (GDPR, IP, Copyright)")

pdf.sub_header("Твоё возражение:")
pdf.body_text(
    "Упомянуты GDPR и правила Meta, но этого мало. Нужна CMP-интеграция для "
    "динамической персонализации и защита от использования чужой интеллектуальной "
    "собственности в генеративном контенте."
)

pdf.sub_header("Что уже есть в документе:")

pdf.bullet(
    "Раздел 7.4 — GDPR/ePrivacy: согласие на трекинг, право на забвение, "
    "прямой отказ от fingerprinting как юридически рискованного."
)
pdf.bullet(
    "Раздел 12.1 — Compliance checker: LLM-ревьюер с промптом из Meta Advertising "
    "Policies перед каждым запуском."
)
pdf.bullet(
    "Раздел 22 (Риски): отдельные строки про права на музыку (royalty-free + "
    "автопроверка), маркировку AI-контента (C2PA), права на людей в кадре "
    "(model releases + автодетекция лиц)."
)
pdf.bullet(
    "Раздел 15.12.7 — приватность кросс-клиентских данных: агрегированные паттерны, "
    "не сырые данные; явное согласие в договоре."
)

pdf.sub_header("Что действительно не хватало (добавляем):")

pdf.bullet(
    "CMP-интеграция (Cookiebot, OneTrust): если пользователь отклонил cookies, "
    "персонализация лендинга невозможна. Система должна это понимать и не считать "
    "отказ от cookies как bounce."
)
pdf.bullet(
    "IP-детекция в генерируемом контенте: LLM может сгенерировать текст, "
    "похожий на чужой слоган или товарный знак. Нужна автоматическая проверка "
    "перед апрувом маркетологом."
)

pdf.verdict_box("ВЕРДИКТ: Частично обосновано. CMP-интеграция и IP-детекция — реальные пробелы, добавлены.", "orange")

pdf.separator()

# ============================================================
# 4. TRUST LAYER
# ============================================================
pdf.section_title(4, "Дашборд прозрачности и Trust Layer")

pdf.sub_header("Твоё возражение:")
pdf.body_text(
    "Модель с оплатой % от прибыли вызывает недоверие. Нужен Attribution & "
    "Incrementality Dashboard с holdout-группами, доказывающий чистый прирост."
)

pdf.sub_header("Что уже есть в документе (и очень подробно):")

pdf.bullet(
    "Раздел 20.10 — прямым текстом: «встроить в продукт рандомизированный "
    "контроль — часть бюджета/трафика работает как раньше. Это измеряет "
    "инкрементальный эффект и делает реальной модель ценообразования "
    "% от подтверждённой экономии»."
)
pdf.bullet(
    "Раздел 21 (Explainability) — объяснения строятся из РЕАЛЬНОЙ СТАТИСТИКИ "
    "модели (credible intervals, effect sizes, sample sizes), не из свободного "
    "LLM-пересказа."
)
pdf.bullet(
    "Раздел 21.2 — конкретный пример дашборда: P(видео #7 — лучший) = 0.78, "
    "CPA $12.40 [CI: $9.30-$16.90], 47 конверсий из 1,230 кликов."
)
pdf.bullet(
    "Раздел 20.11 — ценообразование привязано к подтверждённой дельте прибыли, "
    "измеренной holdout-группой. В нулевом сценарии клиент платит только $500 base."
)
pdf.bullet(
    "Раздел 11.2 — якорные креативы: два референсных креатива в каждой пачке "
    "для отделения эффекта системы от сезонности, с решением по статпорогу "
    "P(разница > X) > 0.9."
)

pdf.verdict_box("ВЕРДИКТ: Полностью закрыто в разделах 20.10, 21 и 11.2.", "green")

pdf.separator()

# ============================================================
# 5. SITE HEALTH GUARD
# ============================================================
pdf.section_title(5, "Мониторинг аномалий на стороне сайта (Site Health Guard)")

pdf.sub_header("Твоё возражение:")
pdf.body_text(
    "Если на стороне клиента сломалась форма оплаты или сайт отдаёт ошибку 500, "
    "ИИ может решить, что креативы не работают, и начнёт выключать прибыльную рекламу. "
    "Система должна отличать падение конверсии от технических неполадок."
)

pdf.sub_header("Что уже есть в документе (частично):")

pdf.bullet(
    "Раздел 11.2 — якорные креативы: если оба якоря упали одновременно с тестовыми, "
    "система решает «рынок, не креатив» и НЕ меняет креативы."
)
pdf.bullet(
    "Раздел 12.5 — алерты на аномалии (CPA вырос на 50%+)."
)
pdf.bullet(
    "Раздел 3.2 — диагностика: «CTR хороший, но Landing Page Views << Clicks — "
    "проверить PageSpeed + пиксель»."
)

pdf.sub_header("Что действительно не хватало (добавляем):")

pdf.body_text(
    "Якорные креативы отлавливают рыночные шоки, но технический сбой сайта — "
    "другой класс проблемы. Если упал платёжный шлюз, якоря тоже падают "
    "(ведут на тот же сайт), и система правильно не меняет креативы... "
    "но продолжает ТРАТИТЬ БЮДЖЕТ на рекламу, ведущую на сломанный сайт."
)

pdf.body_text("Добавляем отдельный слой Site Health Guard:")

pdf.bullet(
    "HTTP health check лендингов (каждые 1-5 мин): автопауза рекламы при 5xx/timeout."
)
pdf.bullet(
    "Синтетический мониторинг (headless browser): проверка формы и платёжного шлюза."
)
pdf.bullet(
    "Детектор аномалий: если CTR стабилен, но CVR упал до нуля за < 1 час — "
    "это не проблема креатива, это сбой сайта. Автопауза + алерт маркетологу."
)

pdf.verdict_box("ВЕРДИКТ: Обосновано. Технический мониторинг сайта — реальный пробел, добавлен.", "orange")

# === SUMMARY PAGE ===
pdf.add_page()
pdf.ln(10)
pdf.set_font("DejaVu", "B", 16)
pdf.set_text_color(30, 60, 120)
pdf.multi_cell(0, 10, "Итоговая таблица")
pdf.ln(8)

col_w = [14, 80, 86]
headers = ["#", "Возражение", "Статус"]

pdf.set_font("DejaVu", "B", 10)
pdf.set_fill_color(30, 60, 120)
pdf.set_text_color(255, 255, 255)
for i, h in enumerate(headers):
    pdf.cell(col_w[i], 9, h, border=1, fill=True, align="C")
pdf.ln()

rows = [
    ("1", "Omnichannel", "Уже учтено (разделы 1, 23 Phase 7)"),
    ("2", "Creative Fatigue", "Уже учтено (разделы 5.2, 6.4, 8.1, 8.2, 13)"),
    ("3", "Compliance / IP", "Частично обосновано — CMP и IP добавлены"),
    ("4", "Trust Layer", "Уже учтено (разделы 20.10, 21, 11.2)"),
    ("5", "Site Health Guard", "Обосновано — мониторинг сайта добавлен"),
]

pdf.set_font("DejaVu", "", 10)
for j, row in enumerate(rows):
    if j % 2 == 0:
        pdf.set_fill_color(245, 247, 255)
    else:
        pdf.set_fill_color(255, 255, 255)

    if "обосновано" in row[2].lower() or "добавлен" in row[2].lower():
        pdf.set_text_color(200, 120, 0)
    else:
        pdf.set_text_color(34, 139, 34)

    for i, val in enumerate(row):
        align = "C" if i == 0 else "L"
        pdf.cell(col_w[i], 9, val, border=1, fill=True, align=align)
    pdf.ln()

pdf.set_text_color(50, 50, 50)
pdf.ln(12)
pdf.set_font("DejaVu", "", 10)
pdf.multi_cell(0, 6, (
    "3 из 5 возражений уже подробно раскрыты в полной версии документа. "
    "2 возражения (Compliance/IP и Site Health Guard) выявили реальные пробелы — "
    "они уже добавлены в архитектуру.\n\n"
    "Давид, рекомендую прочитать полную версию vision.md v1.8 — "
    "там 25 разделов с детальной проработкой каждого аспекта, "
    "включая экономическую модель, расчёт рисков и порядок разработки."
))

pdf.ln(15)
pdf.set_font("DejaVu", "", 10)
pdf.set_text_color(100, 100, 100)
pdf.multi_cell(0, 6, "— Арам, 10 октября 2026", align="R")

output_path = "D:/Marketing-AI/Response-to-David-Objections.pdf"
pdf.output(output_path)
print(f"PDF saved to: {output_path}")
