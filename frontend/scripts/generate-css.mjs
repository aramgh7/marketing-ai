import { execSync } from "child_process";
import { readFileSync, writeFileSync, mkdirSync } from "fs";
import { join, dirname } from "path";
import { fileURLToPath } from "url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const outDir = join(root, "src", "_generated");
const cssOut = join(outDir, "tailwind.css");
const tsOut = join(outDir, "global-css.ts");

mkdirSync(outDir, { recursive: true });

execSync(
  `node "${join(root, "node_modules", ".bin", "tailwindcss")}" -i "${join(root, "src", "app", "globals.css")}" -o "${cssOut}" --minify`,
  { stdio: "inherit", cwd: root }
);

const css = readFileSync(cssOut, "utf8");
writeFileSync(tsOut, `export const globalCss = ${JSON.stringify(css)};\n`);
console.log(`[generate-css] wrote ${(css.length / 1024).toFixed(1)} KB → ${tsOut}`);
