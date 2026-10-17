"""Convert markdown files to PDF using fpdf2."""
import sys
import re
from fpdf import FPDF
from fpdf.enums import XPos, YPos


class MarkdownPDF(FPDF):
    FONT = "DejaVu"

    def __init__(self):
        super().__init__()
        self.set_auto_page_break(auto=True, margin=15)
        self.set_margins(20, 20, 20)
        # Arial Unicode MS — full Unicode including Cyrillic and em-dash
        fonts = "C:/Windows/Fonts"
        self.add_font(self.FONT, "", f"{fonts}/arial.ttf")
        self.add_font(self.FONT, "B", f"{fonts}/arialbd.ttf")
        self.add_font(self.FONT, "I", f"{fonts}/ariali.ttf")
        self.add_font(self.FONT, "BI", f"{fonts}/arialbi.ttf")
        self.add_font("DejaVuMono", "", f"{fonts}/cour.ttf")

    def header(self):
        pass

    def footer(self):
        self.set_y(-15)
        self.set_font(self.FONT, "I", 8)
        self.set_text_color(128, 128, 128)
        self.cell(0, 10, f"Page {self.page_no()}", align="C")

    def render_markdown(self, text: str):
        self.add_page()
        lines = text.split("\n")
        in_table = False
        table_rows = []
        in_code_block = False

        for line in lines:
            # Code block toggle
            if line.startswith("```"):
                if in_table:
                    self._flush_table(table_rows)
                    table_rows = []
                    in_table = False
                in_code_block = not in_code_block
                continue

            if in_code_block:
                self.set_font("DejaVuMono", size=8)
                self.set_text_color(50, 50, 50)
                self.set_fill_color(240, 240, 240)
                safe = line.replace("\t", "    ")
                self.cell(0, 5, safe[:120], new_x=XPos.LMARGIN, new_y=YPos.NEXT, fill=True)
                continue

            # Table detection
            if "|" in line and line.strip().startswith("|"):
                if not in_table:
                    in_table = True
                    table_rows = []
                # Skip separator rows like |---|---|
                if re.match(r"^\|[\s\-|:]+\|$", line.strip()):
                    continue
                cells = [c.strip() for c in line.strip().strip("|").split("|")]
                table_rows.append(cells)
                continue
            else:
                if in_table:
                    self._flush_table(table_rows)
                    table_rows = []
                    in_table = False

            # Skip HTML comments and horizontal rules
            if line.strip().startswith("<!--") or line.strip() == "---":
                self.ln(3)
                continue

            # Blockquote
            if line.startswith(">"):
                content = line.lstrip("> ").strip()
                self.set_font(self.FONT, "I", 9)
                self.set_text_color(100, 100, 100)
                self.set_fill_color(245, 245, 245)
                self.multi_cell(0, 6, self._strip_inline(content), fill=True,
                                new_x=XPos.LMARGIN, new_y=YPos.NEXT)
                self.ln(1)
                continue

            # Headings
            if line.startswith("#### "):
                self._render_heading(line[5:], 11, "B")
            elif line.startswith("### "):
                self._render_heading(line[4:], 12, "B")
            elif line.startswith("## "):
                self._render_heading(line[3:], 14, "B")
            elif line.startswith("# "):
                self._render_heading(line[2:], 18, "B")
            elif line.strip() == "":
                self.ln(3)
            elif line.startswith("- ") or re.match(r"^\d+\. ", line):
                # List item
                prefix = "• " if line.startswith("- ") else re.match(r"^(\d+\. )", line).group(1)
                content = line[2:] if line.startswith("- ") else re.sub(r"^\d+\. ", "", line)
                self.set_font(self.FONT, size=9)
                self.set_text_color(30, 30, 30)
                self.set_x(25)
                self.multi_cell(0, 5, f"{prefix}{self._strip_inline(content)}",
                                new_x=XPos.LMARGIN, new_y=YPos.NEXT)
            else:
                self.set_font(self.FONT, size=9)
                self.set_text_color(30, 30, 30)
                self.multi_cell(0, 5, self._strip_inline(line),
                                new_x=XPos.LMARGIN, new_y=YPos.NEXT)

        if in_table:
            self._flush_table(table_rows)

    def _render_heading(self, text: str, size: int, style: str):
        self.ln(4)
        self.set_font(self.FONT, style, size)
        self.set_text_color(20, 40, 80)
        self.multi_cell(0, size * 0.6, self._strip_inline(text),
                        new_x=XPos.LMARGIN, new_y=YPos.NEXT)
        self.ln(2)

    def _strip_inline(self, text: str) -> str:
        """Remove inline markdown: bold, italic, code, links. Replace unsupported chars."""
        text = re.sub(r"\*\*(.+?)\*\*", r"\1", text)
        text = re.sub(r"\*(.+?)\*", r"\1", text)
        text = re.sub(r"`(.+?)`", r"\1", text)
        text = re.sub(r"\[(.+?)\]\(.+?\)", r"\1", text)
        text = re.sub(r"__(.+?)__", r"\1", text)
        # Replace emoji and other non-Latin1/Cyrillic chars Arial can't render
        emoji_map = {
            "✅": "[OK]", "❌": "[X]", "⚠": "[!]",
            "✔": "[+]", "✖": "[-]", "✨": "*",
            "\U0001f4cb": "", "\U0001f527": "", "\U0001f4ca": "",
        }
        for ch, rep in emoji_map.items():
            text = text.replace(ch, rep)
        # Strip any remaining chars above BMP that Arial likely can't handle
        text = "".join(c if ord(c) < 0x10000 else "?" for c in text)
        return text

    def _flush_table(self, rows: list):
        if not rows:
            return
        self.ln(2)
        col_count = max(len(r) for r in rows)
        page_width = self.w - self.l_margin - self.r_margin
        col_w = page_width / col_count

        for i, row in enumerate(rows):
            self.set_font(self.FONT, "B" if i == 0 else "", 8)
            bg = (220, 230, 245) if i == 0 else (255, 255, 255) if i % 2 == 0 else (245, 248, 255)
            self.set_fill_color(*bg)
            self.set_text_color(20, 20, 20)
            for j, cell in enumerate(row):
                w = col_w
                text = self._strip_inline(cell)[:60]
                self.cell(w, 6, text, border=1, fill=True)
            # fill remaining cols
            for _ in range(col_count - len(row)):
                self.cell(col_w, 6, "", border=1, fill=True)
            self.ln()
        self.ln(3)


def convert(input_path: str, output_path: str):
    with open(input_path, "r", encoding="utf-8") as f:
        content = f.read()
    pdf = MarkdownPDF()
    pdf.render_markdown(content)
    pdf.output(output_path)
    print(f"Created: {output_path}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print("Usage: python md_to_pdf.py <input.md> <output.pdf>")
        sys.exit(1)
    convert(sys.argv[1], sys.argv[2])
