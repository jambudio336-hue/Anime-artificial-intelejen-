#!/usr/bin/env node
/**
 * build.js - sinkronkan versi ke index.html dan tulis update.json.
 */

import { readFileSync, writeFileSync, existsSync } from "node:fs";
import { execSync } from "node:child_process";

const ROOT = new URL("../../", import.meta.url);

function baca(nama) {
  const p = new URL(nama, ROOT);
  if (!existsSync(p)) throw new Error(`berkas tidak ada: ${nama}`);
  return readFileSync(p, "utf8");
}

const versi = JSON.parse(baca("package.json")).version;

if (!/^\d+\.\d+\.\d+$/.test(versi)) {
  throw new Error(`versi tidak valid: ${versi}`);
}

let html = baca("index.html");
const sebelum = html;

html = html.replace(/const APP_VER\s*=\s*"[^"]*"/, `const APP_VER="${versi}"`);

if (html === sebelum) {
  console.warn("[!] APP_VER tidak ketemu di index.html");
} else {
  writeFileSync(new URL("index.html", ROOT), html);
  console.log(`[+] APP_VER diset ke ${versi}`);
}

writeFileSync(
  new URL("update.json", ROOT),
  JSON.stringify(
    {
      version: versi,
      file: "index.html",
      catatan: "auto-generated oleh pkg/node/build.js",
      waktu: new Date().toISOString(),
    },
    null,
    2
  ) + "\n"
);

console.log("[+] update.json ditulis");

const sw = baca("sw.js");
const cache = sw.match(/const CACHE\s*=\s*"([^"]+)"/);

if (cache) {
  const baru = cache[1].replace(/v\d+-\w+$/, `v6-${Date.now().toString(36)}`);
  writeFileSync(new URL("sw.js", ROOT), sw.replace(cache[0], `const CACHE = "${baru}"`));
  console.log(`[+] cache service worker: ${baru}`);
}

try {
  const hash = execSync("git rev-parse --short HEAD", { cwd: ROOT }).toString().trim();
  console.log(`[i] commit: ${hash}`);
} catch {
  console.log("[i] bukan repo git, lewati commit hash");
}

console.log("[v] build selesai");
