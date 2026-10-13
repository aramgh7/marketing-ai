"""
Generate styled HTML + PDF for action-plan.md, roadmap.md, architecture.md
Usage: python generate_docs_pdf.py
Outputs HTML files; then attempts headless Edge/Chrome PDF print.
"""
import pathlib
import html as html_mod
import re
import subprocess
import sys

BASE = pathlib.Path(r'D:/Marketing-AI')
AI_DIR = BASE / '.ai'

DOCS = [
    {
        'md': AI_DIR / 'action-plan.md',
        'html': BASE / 'action-plan.html',
        'pdf': BASE / 'Action-Plan-v1.0.pdf',
        'badge': 'Action Plan',
        'title': 'Marketing AI\nPlatform',
        'subtitle': 'Action Plan — порядок действий',
        'version': '1.0',
        'date': 'October 2026',
        'status': 'Active',
        'lang': 'ru',
    },
    {
        'md': AI_DIR / 'roadmap.md',
        'html': BASE / 'roadmap.html',
        'pdf': BASE / 'Roadmap-v1.0.pdf',
        'badge': 'Roadmap',
        'title': 'Marketing AI\nPlatform',
        'subtitle': 'Development Roadmap — Phase 0 → 7',
        'version': '1.0',
        'date': 'October 2026',
        'status': 'Phase 0 Next',
        'lang': 'ru',
    },
    {
        'md': AI_DIR / 'architecture.md',
        'html': BASE / 'architecture.html',
        'pdf': BASE / 'Architecture-v1.0.pdf',
        'badge': 'Architecture',
        'title': 'Marketing AI\nPlatform',
        'subtitle': 'System Architecture — Spring Boot + FastAPI + Next.js',
        'version': '1.0',
        'date': 'October 2026',
        'status': 'MVP Design',
        'lang': 'en',
    },
]


# ── CSS (shared) ─────────────────────────────────────────────────────────────
CSS = '''
  @import url('https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700;800;900&display=swap');
  @import url('https://fonts.googleapis.com/css2?family=JetBrains+Mono:wght@400;500&display=swap');

  :root {
    --primary: #1a1a2e;
    --accent:  #e94560;
    --accent2: #0f3460;
    --accent3: #16213e;
    --light:   #f8f9fa;
    --gray:    #6c757d;
    --border:  #e2e8f0;
    --text:    #2d3436;
    --text-light: #444;
  }

  * { margin: 0; padding: 0; box-sizing: border-box; }

  body {
    font-family: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
    color: var(--text);
    line-height: 1.65;
    background: white;
    -webkit-print-color-adjust: exact !important;
    print-color-adjust: exact !important;
  }

  @page { size: A4; margin: 18mm 16mm 22mm 16mm; }

  /* ===== COVER ===== */
  .cover {
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
  }
  .cover-content { position: relative; z-index: 1; }
  .cover-badge {
    display: inline-block;
    background: rgba(233,69,96,0.2);
    border: 1px solid rgba(233,69,96,0.4);
    color: var(--accent);
    padding: 5px 16px;
    border-radius: 20px;
    font-size: 10px; font-weight: 700;
    letter-spacing: 2px;
    text-transform: uppercase;
    margin-bottom: 30px;
  }
  .cover-title {
    font-size: 52px !important; font-weight: 900 !important;
    line-height: 1.1 !important; margin-bottom: 14px !important;
    letter-spacing: -1px !important; color: white !important;
    border: none !important; display: block !important; padding: 0 !important;
    white-space: pre-line;
  }
  .cover-title span { color: var(--accent) !important; }
  .cover-subtitle {
    font-size: 16px !important; font-weight: 300 !important;
    color: rgba(255,255,255,0.6) !important; margin-bottom: 50px !important;
  }
  .cover-meta { display: flex; gap: 36px; margin-bottom: 55px; }
  .cover-meta-item { border-left: 2px solid rgba(233,69,96,0.4); padding-left: 14px; }
  .cover-meta-label {
    font-size: 9px; text-transform: uppercase; letter-spacing: 1.5px;
    color: rgba(255,255,255,0.35); margin-bottom: 2px; font-weight: 600;
  }
  .cover-meta-value { font-size: 16px; font-weight: 700; color: white; }
  .cover-tagline {
    font-size: 11px; color: rgba(255,255,255,0.35); line-height: 1.8;
    border-top: 1px solid rgba(255,255,255,0.08); padding-top: 25px;
  }
  .cover-footer {
    position: absolute; bottom: 25mm; left: 22mm;
    font-size: 9px; color: rgba(255,255,255,0.2); letter-spacing: 0.5px;
  }

  /* ===== DOCUMENT BODY ===== */
  .document-body { padding-top: 8px; }

  h1 {
    font-size: 22px; font-weight: 800; color: var(--primary);
    margin: 30px 0 12px 0; display: none;
  }
  .section-header {
    margin-top: 28px; margin-bottom: 10px;
    page-break-after: avoid; page-break-inside: avoid;
    border-left: 4px solid var(--accent); padding-left: 14px;
  }
  .section-tag {
    display: inline-block;
    background: rgba(233,69,96,0.08); color: var(--accent);
    padding: 2px 10px; border-radius: 10px;
    font-size: 8px; font-weight: 700; letter-spacing: 1.5px;
    text-transform: uppercase; margin-bottom: 4px;
  }
  h2 {
    font-size: 16px; font-weight: 800; color: var(--primary);
    letter-spacing: -0.2px; line-height: 1.3; margin: 0;
  }
  h3 {
    font-size: 12px; font-weight: 700; color: var(--accent2);
    margin: 16px 0 5px 0; page-break-after: avoid;
    padding-bottom: 3px; border-bottom: 1px solid #eef0f3;
  }
  h4 {
    font-size: 10.5px; font-weight: 700; color: var(--accent3);
    margin: 12px 0 4px 0; page-break-after: avoid;
  }
  h5, h6 { font-size: 10px; font-weight: 600; color: var(--text); margin: 8px 0 3px 0; }

  p {
    font-size: 10px; line-height: 1.65;
    color: var(--text-light); margin: 4px 0;
  }
  strong { color: var(--primary); font-weight: 700; }
  a { color: var(--accent2); text-decoration: none; border-bottom: 1px solid rgba(15,52,96,0.15); }
  del { color: var(--gray); }

  /* ===== LISTS ===== */
  ul, ol { margin: 5px 0 5px 18px; font-size: 10px; color: var(--text-light); }
  li { margin: 2px 0; line-height: 1.6; padding-left: 3px; }
  li::marker { color: var(--accent); font-weight: 700; }

  /* Checkboxes */
  .cb-done { color: #22c55e; font-weight: 700; }
  .cb-todo { color: var(--gray); }

  /* ===== TABLES ===== */
  .table-wrapper {
    margin: 8px 0; page-break-inside: auto;
    overflow: hidden; border-radius: 6px;
    box-shadow: 0 1px 3px rgba(0,0,0,0.06);
  }
  table { width: 100%; border-collapse: collapse; font-size: 9px; }
  thead { background: var(--primary); }
  th {
    color: white; padding: 6px 8px; text-align: left;
    font-weight: 600; font-size: 8px;
    text-transform: uppercase; letter-spacing: 0.4px; border: none;
  }
  td {
    padding: 5px 8px; border-bottom: 1px solid #eef0f3;
    color: var(--text-light); line-height: 1.5;
    vertical-align: top; font-size: 9px;
  }
  tbody tr:nth-child(even) td { background: #f8f9fb; }
  tbody tr:last-child td { border-bottom: none; }

  /* ===== CODE ===== */
  pre.code-block {
    background: var(--primary); color: #cbd5e0;
    border-radius: 6px; padding: 12px 14px;
    font-family: 'JetBrains Mono', 'Cascadia Code', 'Consolas', monospace;
    font-size: 7.5px; line-height: 1.55;
    overflow-x: auto; white-space: pre-wrap; word-wrap: break-word;
    margin: 8px 0; page-break-inside: avoid;
  }
  pre.code-block code { background: none; padding: 0; border-radius: 0; font-size: inherit; color: inherit; }
  code {
    background: #eef1f6; padding: 1px 5px; border-radius: 3px;
    font-family: 'JetBrains Mono', 'Cascadia Code', 'Consolas', monospace;
    font-size: 8.5px; color: var(--accent2);
  }

  /* ===== BLOCKQUOTES ===== */
  blockquote {
    border-left: 3px solid var(--accent); margin: 8px 0;
    padding: 8px 14px;
    background: rgba(233,69,96,0.03);
    border-radius: 0 6px 6px 0;
  }
  blockquote p { font-size: 9.5px; color: var(--accent3); margin: 2px 0; line-height: 1.6; }

  /* ===== WARNING ===== */
  .warning-text {
    background: #fffce6; border-left: 3px solid #f0c040;
    padding: 2px 8px; display: inline;
    border-radius: 0 3px 3px 0; font-size: inherit;
  }

  /* ===== DIVIDER ===== */
  hr.section-divider {
    border: none; height: 2px;
    background: linear-gradient(90deg, var(--accent) 0%, var(--border) 25%, transparent 100%);
    margin: 22px 0 18px 0;
  }

  /* ===== PRINT ===== */
  .section-header, h3, h4 { page-break-after: avoid; }
  pre.code-block, blockquote { page-break-inside: avoid; }
'''


# ── Markdown → HTML ──────────────────────────────────────────────────────────
def inline(t: str) -> str:
    t = html_mod.escape(t)
    t = re.sub(r'\*\*(.+?)\*\*', r'<strong>\1</strong>', t)
    t = re.sub(r'(?<!\*)\*([^*]+?)\*(?!\*)', r'<em>\1</em>', t)
    t = re.sub(r'`([^`]+)`', r'<code>\1</code>', t)
    t = re.sub(r'\[([^\]]+)\]\(([^)]+)\)', r'<a href="\2">\1</a>', t)
    t = re.sub(r'~~(.+?)~~', r'<del>\1</del>', t)
    # Checkboxes
    t = re.sub(r'\[x\]', r'<span class="cb-done">✅</span>', t, flags=re.IGNORECASE)
    t = re.sub(r'\[ \]', r'<span class="cb-todo">☐</span>', t)
    if '⚠️' in t:
        t = f'<span class="warning-text">{t}</span>'
    return t


def md_to_html(md: str) -> str:
    lines = md.split('\n')
    parts = []
    in_table = in_code = in_blockquote = in_ul = in_ol = False
    bq_buffer = []

    def close_list():
        nonlocal in_ul, in_ol
        if in_ul:
            parts.append('</ul>'); in_ul = False
        if in_ol:
            parts.append('</ol>'); in_ol = False

    def close_table():
        nonlocal in_table
        if in_table:
            parts.append('</tbody></table></div>'); in_table = False

    def close_bq():
        nonlocal in_blockquote, bq_buffer
        if in_blockquote:
            parts.append(f'<blockquote>{"".join(bq_buffer)}</blockquote>')
            bq_buffer = []; in_blockquote = False

    for line in lines:
        s = line.strip()

        # Code block fence
        if s.startswith('```'):
            close_list(); close_table(); close_bq()
            if in_code:
                parts.append('</code></pre>'); in_code = False
            else:
                parts.append('<pre class="code-block"><code>'); in_code = True
            continue
        if in_code:
            parts.append(html_mod.escape(line) + '\n')
            continue

        # Empty line
        if not s:
            close_list(); close_bq()
            if in_table: close_table()
            continue

        # Blockquote
        if s.startswith('>'):
            close_list(); close_table()
            content = s[1:].strip()
            if not in_blockquote:
                in_blockquote = True; bq_buffer = []
            bq_buffer.append(f'<p>{inline(content)}</p>')
            continue
        elif in_blockquote:
            close_bq()

        # HR
        if s == '---':
            close_list(); close_table(); close_bq()
            parts.append('<hr class="section-divider">')
            continue

        # Headers
        hm = re.match(r'^(#{1,6})\s+(.+)$', s)
        if hm:
            close_list(); close_table(); close_bq()
            level = len(hm.group(1))
            text = hm.group(2)
            if level == 1:
                parts.append(f'<h1>{inline(text)}</h1>')
            elif level == 2:
                sm = re.match(r'^(Phase\s+\S+|Этап\s+\d+|##?\s*\d+[-.]\d*|Step\s+\d+|[A-Z][^-]+)', text)
                parts.append(
                    f'<div class="section-header">'
                    f'<h2>{inline(text)}</h2>'
                    f'</div>'
                )
            elif level == 3:
                parts.append(f'<h3>{inline(text)}</h3>')
            elif level == 4:
                parts.append(f'<h4>{inline(text)}</h4>')
            else:
                parts.append(f'<h{level}>{inline(text)}</h{level}>')
            continue

        # Table
        if '|' in s and s.startswith('|'):
            close_list(); close_bq()
            cells = [c.strip() for c in s.split('|')[1:-1]]
            if all(re.match(r'^[-:]+$', c) for c in cells):
                continue
            if not in_table:
                in_table = True
                parts.append('<div class="table-wrapper"><table><thead>')
                parts.append('<tr>' + ''.join(f'<th>{inline(c)}</th>' for c in cells) + '</tr>')
                parts.append('</thead><tbody>')
            else:
                parts.append('<tr>' + ''.join(f'<td>{inline(c)}</td>' for c in cells) + '</tr>')
            continue
        elif in_table:
            close_table()

        # Unordered list (including sub-items with indentation)
        ul_m = re.match(r'^(\s*)[-*]\s+(.+)$', line)
        if ul_m:
            close_table()
            indent = len(ul_m.group(1))
            content = ul_m.group(2)
            if not in_ul:
                close_list(); parts.append('<ul>'); in_ul = True
            parts.append(f'<li>{inline(content)}</li>')
            continue

        # Ordered list
        ol_m = re.match(r'^(\d+)\.\s+(.+)$', s)
        if ol_m and not re.match(r'^#+\s', s):
            close_table()
            if not in_ol:
                close_list(); parts.append('<ol>'); in_ol = True
            parts.append(f'<li>{inline(ol_m.group(2))}</li>')
            continue

        close_list(); close_table()
        parts.append(f'<p>{inline(s)}</p>')

    close_list(); close_table(); close_bq()
    if in_code:
        parts.append('</code></pre>')
    return '\n'.join(parts)


# ── Cover page builder ────────────────────────────────────────────────────────
def make_cover(doc: dict) -> str:
    title_html = html_mod.escape(doc['title']).replace('\n', '<br>')
    last_word = title_html.rsplit('<br>', 1)[-1] if '<br>' in title_html else title_html.rsplit(' ', 1)[-1]
    first_part = title_html.rsplit('<br>', 1)[0] + '<br>' if '<br>' in title_html else ' '.join(title_html.split()[:-1]) + ' '
    return f'''
<div class="cover">
  <div class="cover-content">
    <div class="cover-badge">{html_mod.escape(doc["badge"])}</div>
    <h1 class="cover-title">{first_part}<span>{last_word}</span></h1>
    <p class="cover-subtitle">{html_mod.escape(doc["subtitle"])}</p>
    <div class="cover-meta">
      <div class="cover-meta-item">
        <div class="cover-meta-label">Version</div>
        <div class="cover-meta-value">{doc["version"]}</div>
      </div>
      <div class="cover-meta-item">
        <div class="cover-meta-label">Date</div>
        <div class="cover-meta-value">{doc["date"]}</div>
      </div>
      <div class="cover-meta-item">
        <div class="cover-meta-label">Status</div>
        <div class="cover-meta-value">{html_mod.escape(doc["status"])}</div>
      </div>
    </div>
    <div class="cover-tagline">
      Marketing AI Platform &bull; AI-powered performance marketing system
    </div>
  </div>
  <div class="cover-footer">Marketing AI Platform &bull; {html_mod.escape(doc["badge"])} &bull; {doc["date"]}</div>
</div>
'''


# ── Full HTML document ────────────────────────────────────────────────────────
def make_html(doc: dict, body: str) -> str:
    cover = make_cover(doc)
    title_esc = html_mod.escape(f"Marketing AI Platform — {doc['badge']}")
    return f'''<!DOCTYPE html>
<html lang="{doc["lang"]}">
<head>
<meta charset="UTF-8">
<title>{title_esc}</title>
<style>{CSS}</style>
</head>
<body>
{cover}
<div class="document-body">
{body}
</div>
</body>
</html>'''


# ── PDF via headless Edge/Chrome ──────────────────────────────────────────────
EDGE_PATHS = [
    r'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe',
    r'C:\Program Files\Microsoft\Edge\Application\msedge.exe',
]
CHROME_PATHS = [
    r'C:\Program Files\Google\Chrome\Application\chrome.exe',
    r'C:\Program Files (x86)\Google\Chrome\Application\chrome.exe',
]


def find_browser():
    for p in EDGE_PATHS + CHROME_PATHS:
        if pathlib.Path(p).exists():
            return p
    return None


def html_to_pdf(html_path: pathlib.Path, pdf_path: pathlib.Path, browser: str):
    cmd = [
        browser,
        '--headless=new',
        '--disable-gpu',
        '--no-sandbox',
        f'--print-to-pdf={pdf_path}',
        '--print-to-pdf-no-header',
        str(html_path.resolve()),
    ]
    result = subprocess.run(cmd, capture_output=True, timeout=120)
    return result.returncode == 0


# ── Main ──────────────────────────────────────────────────────────────────────
def main():
    browser = find_browser()
    if not browser:
        print('⚠️  No Edge/Chrome found — HTML files will be generated, open manually for PDF.')

    for doc in DOCS:
        md_path = doc['md']
        if not md_path.exists():
            print(f'  SKIP {md_path.name} — file not found')
            continue

        md = md_path.read_text(encoding='utf-8')
        body = md_to_html(md)
        html_doc = make_html(doc, body)

        html_path = doc['html']
        html_path.write_text(html_doc, encoding='utf-8')
        print(f'  HTML -> {html_path.name}  ({len(html_doc):,} chars)')

        if browser:
            pdf_path = doc['pdf']
            ok = html_to_pdf(html_path, pdf_path, browser)
            if ok:
                print(f'  PDF  -> {pdf_path.name}  OK')
            else:
                print(f'  PDF  -> {pdf_path.name}  FAIL  open HTML manually: Ctrl+P -> Save as PDF')
        else:
            print(f'         Open {html_path.name} in browser -> Ctrl+P -> Save as PDF')

    print('\nDone.')


if __name__ == '__main__':
    main()
