#!/usr/bin/env python3
"""
Embed full source code into ml-math-audit.md.
For each ### N.M `path` section, inserts ### Полный код + fenced block
before the closing --- separator.
"""
import os
import re

BASE_DIR = r"D:\Marketing-AI"
AUDIT_FILE = os.path.join(BASE_DIR, ".ai", "ml-math-audit.md")


def find_file(rel_or_dotted: str) -> str | None:
    """Resolve path; handle platform-api/.../X.java by walking the tree."""
    if "..." not in rel_or_dotted:
        p = os.path.join(BASE_DIR, rel_or_dotted.replace("/", os.sep))
        return p if os.path.isfile(p) else None
    # Java shorthand: find by filename
    fname = rel_or_dotted.split("/")[-1]
    for root, _dirs, files in os.walk(os.path.join(BASE_DIR, "platform-api")):
        if fname in files:
            return os.path.join(root, fname)
    return None


def read_source(path: str | None) -> str:
    if path is None:
        return "// Source file not found"
    try:
        with open(path, "r", encoding="utf-8") as f:
            return f.read().rstrip()
    except Exception as e:
        return f"// Could not read file: {e}"


def lang(path: str | None) -> str:
    if path and path.endswith(".java"):
        return "java"
    return "python"


def main() -> None:
    with open(AUDIT_FILE, "r", encoding="utf-8") as f:
        content = f.read()

    lines = content.split("\n")
    output: list[str] = []
    current_path: str | None = None   # tracks active ### N.M section's file path
    has_content = False                # at least one non-blank line seen since header

    header_re = re.compile(r"^### \d+\.\d+ `([^`]+)`")

    for line in lines:
        m = header_re.match(line)
        if m:
            current_path = m.group(1)
            has_content = False
            output.append(line)
            continue

        if line == "---" and current_path is not None and has_content:
            # Insert code block before this separator
            src_path = find_file(current_path)
            src = read_source(src_path)
            output.append("")
            output.append("### Полный код")
            output.append("")
            output.append(f"```{lang(src_path)}")
            output.append(src)
            output.append("```")
            output.append("")
            current_path = None
            has_content = False
            output.append(line)
            continue

        if current_path is not None and line.strip():
            has_content = True

        output.append(line)

    with open(AUDIT_FILE, "w", encoding="utf-8") as f:
        f.write("\n".join(output))

    print(f"Done. Written to {AUDIT_FILE}")


if __name__ == "__main__":
    main()
