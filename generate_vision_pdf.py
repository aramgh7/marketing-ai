"""Convert vision-public.md to a professionally styled HTML for PDF generation via Edge headless."""
import pathlib
import html as html_mod
import re

MD_PATH = pathlib.Path(r'D:/Marketing-AI/.ai/vision-public.md')
HTML_PATH = pathlib.Path(r'D:/Marketing-AI/.ai/vision-public-styled.html')

md = MD_PATH.read_text(encoding='utf-8')


def inline(t):
    """Convert inline markdown to HTML."""
    t = html_mod.escape(t)
    t = re.sub(r'\*\*(.+?)\*\*', r'<strong>\1</strong>', t)
    t = re.sub(r'(?<!\*)\*([^*]+?)\*(?!\*)', r'<em>\1</em>', t)
    t = re.sub(r'`([^`]+)`', r'<code>\1</code>', t)
    t = re.sub(r'\[([^\]]+)\]\(([^)]+)\)', r'<a href="\2">\1</a>', t)
    t = re.sub(r'~~(.+?)~~', r'<del>\1</del>', t)
    if '\u26a0\ufe0f' in t:  # ⚠️
        t = f'<span class="warning-text">{t}</span>'
    return t


lines = md.split('\n')
html_parts = []
in_table = False
in_code = False
in_blockquote = False
in_ul = False
in_ol = False
bq_buffer = []
section_count = 0


def close_list():
    global in_ul, in_ol
    if in_ul:
        html_parts.append('</ul>')
        in_ul = False
    if in_ol:
        html_parts.append('</ol>')
        in_ol = False


def close_table():
    global in_table
    if in_table:
        html_parts.append('</tbody></table></div>')
        in_table = False


def close_blockquote():
    global in_blockquote, bq_buffer
    if in_blockquote:
        html_parts.append(f'<blockquote>{"".join(bq_buffer)}</blockquote>')
        bq_buffer = []
        in_blockquote = False


for i, line in enumerate(lines):
    stripped = line.strip()

    # Code blocks
    if stripped.startswith('```'):
        close_list()
        close_table()
        close_blockquote()
        if in_code:
            html_parts.append('</code></pre>')
            in_code = False
        else:
            html_parts.append('<pre class="code-block"><code>')
            in_code = True
        continue
    if in_code:
        html_parts.append(html_mod.escape(line) + '\n')
        continue

    # Empty line
    if not stripped:
        close_list()
        close_blockquote()
        if in_table:
            close_table()
        continue

    # Blockquote
    if stripped.startswith('>'):
        close_list()
        close_table()
        content = stripped[1:].strip()
        if not in_blockquote:
            in_blockquote = True
            bq_buffer = []
        bq_buffer.append(f'<p>{inline(content)}</p>')
        continue
    elif in_blockquote:
        close_blockquote()

    # Horizontal rule
    if stripped == '---':
        close_list()
        close_table()
        close_blockquote()
        html_parts.append('<hr class="section-divider">')
        continue

    # Headers
    header_match = re.match(r'^(#{1,6})\s+(.+)$', stripped)
    if header_match:
        close_list()
        close_table()
        close_blockquote()
        level = len(header_match.group(1))
        text = header_match.group(2)

        if level == 1:
            html_parts.append(f'<h1>{inline(text)}</h1>')
        elif level == 2:
            section_count += 1
            sec_match = re.match(r'^(\d+)\.\s*(.+)$', text)
            if sec_match:
                sec_num = sec_match.group(1)
                sec_title = sec_match.group(2)
                html_parts.append(
                    f'<div class="section-header">'
                    f'<span class="section-tag">Section {sec_num}</span>'
                    f'<h2>{inline(sec_title)}</h2>'
                    f'</div>'
                )
            else:
                html_parts.append(
                    f'<div class="section-header"><h2>{inline(text)}</h2></div>'
                )
        elif level == 3:
            html_parts.append(f'<h3>{inline(text)}</h3>')
        elif level == 4:
            html_parts.append(f'<h4>{inline(text)}</h4>')
        else:
            html_parts.append(f'<h{level}>{inline(text)}</h{level}>')
        continue

    # Table
    if '|' in stripped and stripped.startswith('|'):
        close_list()
        close_blockquote()
        cells = [c.strip() for c in stripped.split('|')[1:-1]]
        if all(re.match(r'^[-:]+$', c) for c in cells):
            continue
        if not in_table:
            in_table = True
            html_parts.append('<div class="table-wrapper"><table><thead>')
            html_parts.append(
                '<tr>' + ''.join(f'<th>{inline(c)}</th>' for c in cells) + '</tr>'
            )
            html_parts.append('</thead><tbody>')
        else:
            html_parts.append(
                '<tr>' + ''.join(f'<td>{inline(c)}</td>' for c in cells) + '</tr>'
            )
        continue
    elif in_table:
        close_table()

    # Unordered list
    ul_match = re.match(r'^[-*]\s+(.+)$', stripped)
    if ul_match:
        close_table()
        if not in_ul:
            close_list()
            html_parts.append('<ul>')
            in_ul = True
        html_parts.append(f'<li>{inline(ul_match.group(1))}</li>')
        continue

    # Ordered list
    ol_match = re.match(r'^(\d+)\.\s+(.+)$', stripped)
    if ol_match:
        close_table()
        if not in_ol:
            close_list()
            html_parts.append('<ol>')
            in_ol = True
        html_parts.append(f'<li>{inline(ol_match.group(2))}</li>')
        continue

    close_list()
    close_table()

    # Regular paragraph
    html_parts.append(f'<p>{inline(stripped)}</p>')

close_list()
close_table()
close_blockquote()
if in_code:
    html_parts.append('</code></pre>')

body = '\n'.join(html_parts)

# ── Cover page ────────────────────────────────────────────────────
cover = '''
<div class="cover">
  <div class="cover-content">
    <div class="cover-badge">Architecture Document</div>
    <h1 class="cover-title">Marketing AI<br><span>Platform</span></h1>
    <p class="cover-subtitle">Vision &amp; Architecture &mdash; Public Version</p>
    <div class="cover-meta">
      <div class="cover-meta-item">
        <div class="cover-meta-label">Version</div>
        <div class="cover-meta-value">1.9.2</div>
      </div>
      <div class="cover-meta-item">
        <div class="cover-meta-label">Date</div>
        <div class="cover-meta-value">October 2026</div>
      </div>
      <div class="cover-meta-item">
        <div class="cover-meta-label">Status</div>
        <div class="cover-meta-value">Concept &amp; Design</div>
      </div>
      <div class="cover-meta-item">
        <div class="cover-meta-label">Sections</div>
        <div class="cover-meta-value">26</div>
      </div>
    </div>
    <div class="cover-tagline">
      \u0417\u0430\u043c\u043a\u043d\u0443\u0442\u0430\u044f \u0418\u0418-\u0441\u0438\u0441\u0442\u0435\u043c\u0430 \u043f\u043e\u043b\u043d\u043e\u0433\u043e \u0446\u0438\u043a\u043b\u0430 performance-\u043c\u0430\u0440\u043a\u0435\u0442\u0438\u043d\u0433\u0430:<br>
      \u0433\u0435\u043d\u0435\u0440\u0430\u0446\u0438\u044f &rarr; \u0442\u0435\u0441\u0442\u0438\u0440\u043e\u0432\u0430\u043d\u0438\u0435 &rarr; \u0430\u043d\u0430\u043b\u0438\u0437 &rarr; \u0441\u0430\u043c\u043e\u043e\u0431\u0443\u0447\u0435\u043d\u0438\u0435 &rarr; \u043f\u0435\u0440\u0441\u043e\u043d\u0430\u043b\u0438\u0437\u0430\u0446\u0438\u044f
    </div>
  </div>
  <div class="cover-footer">Marketing AI Platform &bull; Public Version &bull; October 2026</div>
</div>
'''

# ── Full HTML document ────────────────────────────────────────────
html_doc = f'''<!DOCTYPE html>
<html lang="ru">
<head>
<meta charset="UTF-8">
<title>Marketing AI Platform — Vision &amp; Architecture (Public)</title>
<style>
  @import url('https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700;800;900&display=swap');
  @import url('https://fonts.googleapis.com/css2?family=JetBrains+Mono:wght@400;500&display=swap');

  :root {{
    --primary: #1a1a2e;
    --accent: #e94560;
    --accent2: #0f3460;
    --accent3: #16213e;
    --light: #f8f9fa;
    --gray: #6c757d;
    --border: #e2e8f0;
    --text: #2d3436;
    --text-light: #444;
  }}

  * {{ margin: 0; padding: 0; box-sizing: border-box; }}

  body {{
    font-family: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
    color: var(--text);
    line-height: 1.65;
    background: white;
    -webkit-print-color-adjust: exact !important;
    print-color-adjust: exact !important;
  }}

  @page {{
    size: A4;
    margin: 18mm 16mm 22mm 16mm;
  }}

  /* ===== COVER ===== */
  .cover {{
    background: linear-gradient(135deg, var(--primary) 0%, var(--accent3) 40%, var(--accent2) 100%);
    color: white;
    display: flex;
    flex-direction: column;
    justify-content: center;
    position: relative;
    page-break-after: always;
    margin: -18mm -16mm 0 -16mm;
    padding: 55mm 22mm 30mm 22mm;
    min-height: calc(297mm);
    width: calc(100% + 32mm);
  }}

  .cover-content {{ position: relative; z-index: 1; }}

  .cover-badge {{
    display: inline-block;
    background: rgba(233, 69, 96, 0.2);
    border: 1px solid rgba(233, 69, 96, 0.4);
    color: var(--accent);
    padding: 5px 16px;
    border-radius: 20px;
    font-size: 10px;
    font-weight: 700;
    letter-spacing: 2px;
    text-transform: uppercase;
    margin-bottom: 30px;
  }}

  .cover-title {{
    font-size: 52px !important;
    font-weight: 900 !important;
    line-height: 1.1 !important;
    margin-bottom: 14px !important;
    letter-spacing: -1px !important;
    color: white !important;
    border: none !important;
    display: block !important;
    padding: 0 !important;
  }}

  .cover-title span {{ color: var(--accent) !important; }}

  .cover-subtitle {{
    font-size: 18px !important;
    font-weight: 300 !important;
    color: rgba(255,255,255,0.6) !important;
    margin-bottom: 50px !important;
  }}

  .cover-meta {{
    display: flex;
    gap: 36px;
    margin-bottom: 55px;
  }}

  .cover-meta-item {{
    border-left: 2px solid rgba(233, 69, 96, 0.4);
    padding-left: 14px;
  }}

  .cover-meta-label {{
    font-size: 9px;
    text-transform: uppercase;
    letter-spacing: 1.5px;
    color: rgba(255,255,255,0.35);
    margin-bottom: 2px;
    font-weight: 600;
  }}

  .cover-meta-value {{
    font-size: 16px;
    font-weight: 700;
    color: white;
  }}

  .cover-tagline {{
    font-size: 12px;
    color: rgba(255,255,255,0.4);
    line-height: 1.8;
    max-width: 500px;
    border-top: 1px solid rgba(255,255,255,0.08);
    padding-top: 25px;
  }}

  .cover-footer {{
    position: absolute;
    bottom: 25mm;
    left: 22mm;
    font-size: 9px;
    color: rgba(255,255,255,0.2);
    letter-spacing: 0.5px;
  }}

  /* ===== DOCUMENT BODY ===== */
  .document-body {{
    padding-top: 8px;
  }}

  /* ===== HEADINGS ===== */
  h1 {{
    font-size: 22px;
    font-weight: 800;
    color: var(--primary);
    margin: 30px 0 12px 0;
    display: none;
  }}

  .section-header {{
    margin-top: 28px;
    margin-bottom: 10px;
    page-break-after: avoid;
    page-break-inside: avoid;
    border-left: 4px solid var(--accent);
    padding-left: 14px;
  }}

  .section-tag {{
    display: inline-block;
    background: rgba(233, 69, 96, 0.08);
    color: var(--accent);
    padding: 2px 10px;
    border-radius: 10px;
    font-size: 8px;
    font-weight: 700;
    letter-spacing: 1.5px;
    text-transform: uppercase;
    margin-bottom: 4px;
  }}

  h2 {{
    font-size: 16px;
    font-weight: 800;
    color: var(--primary);
    letter-spacing: -0.2px;
    line-height: 1.3;
    margin: 0;
  }}

  h3 {{
    font-size: 12px;
    font-weight: 700;
    color: var(--accent2);
    margin: 16px 0 5px 0;
    page-break-after: avoid;
    padding-bottom: 3px;
    border-bottom: 1px solid #eef0f3;
  }}

  h4 {{
    font-size: 10.5px;
    font-weight: 700;
    color: var(--accent3);
    margin: 12px 0 4px 0;
    page-break-after: avoid;
  }}

  h5, h6 {{
    font-size: 10px;
    font-weight: 600;
    color: var(--text);
    margin: 8px 0 3px 0;
  }}

  /* ===== TEXT ===== */
  p {{
    font-size: 10px;
    line-height: 1.65;
    color: var(--text-light);
    margin: 4px 0;
  }}

  strong {{ color: var(--primary); font-weight: 700; }}

  a {{
    color: var(--accent2);
    text-decoration: none;
    border-bottom: 1px solid rgba(15, 52, 96, 0.15);
  }}

  del {{ color: var(--gray); }}

  /* ===== LISTS ===== */
  ul, ol {{
    margin: 5px 0 5px 18px;
    font-size: 10px;
    color: var(--text-light);
  }}

  li {{
    margin: 2px 0;
    line-height: 1.6;
    padding-left: 3px;
  }}

  li::marker {{
    color: var(--accent);
    font-weight: 700;
  }}

  /* ===== TABLES ===== */
  .table-wrapper {{
    margin: 8px 0;
    page-break-inside: auto;
    overflow: hidden;
    border-radius: 6px;
    box-shadow: 0 1px 3px rgba(0,0,0,0.06);
  }}

  table {{
    width: 100%;
    border-collapse: collapse;
    font-size: 9px;
  }}

  thead {{
    background: var(--primary);
  }}

  th {{
    color: white;
    padding: 6px 8px;
    text-align: left;
    font-weight: 600;
    font-size: 8px;
    text-transform: uppercase;
    letter-spacing: 0.4px;
    border: none;
  }}

  td {{
    padding: 5px 8px;
    border-bottom: 1px solid #eef0f3;
    color: var(--text-light);
    line-height: 1.5;
    vertical-align: top;
    font-size: 9px;
  }}

  tbody tr:nth-child(even) td {{ background: #f8f9fb; }}
  tbody tr:last-child td {{ border-bottom: none; }}

  /* ===== CODE ===== */
  pre.code-block {{
    background: var(--primary);
    color: #cbd5e0;
    border-radius: 6px;
    padding: 12px 14px;
    font-family: 'JetBrains Mono', 'Cascadia Code', 'Consolas', monospace;
    font-size: 8px;
    line-height: 1.55;
    overflow-x: auto;
    white-space: pre-wrap;
    word-wrap: break-word;
    margin: 8px 0;
    page-break-inside: avoid;
  }}

  pre.code-block code {{
    background: none;
    padding: 0;
    border-radius: 0;
    font-size: inherit;
    color: inherit;
  }}

  code {{
    background: #eef1f6;
    padding: 1px 5px;
    border-radius: 3px;
    font-family: 'JetBrains Mono', 'Cascadia Code', 'Consolas', monospace;
    font-size: 8.5px;
    color: var(--accent2);
  }}

  /* ===== BLOCKQUOTES ===== */
  blockquote {{
    border-left: 3px solid var(--accent);
    margin: 8px 0;
    padding: 8px 14px;
    background: rgba(233, 69, 96, 0.03);
    border-radius: 0 6px 6px 0;
  }}

  blockquote p {{
    font-size: 9.5px;
    color: var(--accent3);
    margin: 2px 0;
    line-height: 1.6;
  }}

  /* ===== WARNING TEXT ===== */
  .warning-text {{
    background: #fffce6;
    border-left: 3px solid #f0c040;
    padding: 2px 8px;
    display: inline;
    border-radius: 0 3px 3px 0;
    font-size: inherit;
  }}

  /* ===== SECTION DIVIDER ===== */
  hr.section-divider {{
    border: none;
    height: 2px;
    background: linear-gradient(90deg, var(--accent) 0%, var(--border) 25%, transparent 100%);
    margin: 22px 0 18px 0;
  }}

  /* ===== PRINT HELPERS ===== */
  .section-header, h3, h4 {{
    page-break-after: avoid;
  }}

  pre.code-block, blockquote {{
    page-break-inside: avoid;
  }}
</style>
</head>
<body>
{cover}
<div class="document-body">
{body}
</div>
</body>
</html>'''

HTML_PATH.write_text(html_doc, encoding='utf-8')
print(f'HTML written: {len(html_doc):,} chars -> {HTML_PATH}')
