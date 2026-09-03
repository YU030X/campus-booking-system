#!/usr/bin/env node
/**
 * T13 slice 4 - offline redaction for T13 E2E artifact TEXT files.
 *
 * Scope guard: processes ONLY the directory given as argv[2] (recursive).
 * Classification (fail-closed, no silent skip-then-pass):
 *   - text extensions (.json .jsonl .md .txt .log .xml .properties .csv .tsv
 *     .har .html .htm .yaml .yml .ini .cfg .conf .eml .headers): redacted.
 *   - known binary extensions (.png .jpg ...): recorded as SKIPPED_BINARY,
 *     never auto-claimed redacted.
 *   - ANY OTHER extension: sniffed; text-like content is redacted like text,
 *     binary-like content (NUL bytes in the first 8 KiB) is recorded as
 *     UNSCANNED_BINARY_UNLISTED_EXT and forces a FAIL-CLOSED exit 2.
 *   - files over 64MB CANNOT be safely redacted by this bounded reader: they
 *     are recorded as OVERSIZE_UNREDACTED and force a FAIL-CLOSED exit 2.
 * Never touches files outside the target directory - T08 originals are copied
 * first by run.ps1.
 *
 * Rules (replacement keeps ONLY capture group 1 - the trusted prefix - and
 * appends a marker; the matched secret itself is never re-emitted):
 *   R1  Authorization: Bearer <token>  -> <prefix>***REDACTED***
 *   R1b Authorization: Basic <b64>     -> <prefix>***REDACTED***
 *   R1c api-key / x-api-key header     -> <prefix>***REDACTED***
 *   R2  (Set-)Cookie header values     -> <prefix>***REDACTED***
 *   R3a JSON sensitive fields          -> <prefix>"***"
 *   R3b key=value sensitive fields     -> <prefix>***
 *   R4  email addresses                -> ***REDACTED-EMAIL*** (no keep)
 *   R5  phones (CN mobile / 11-digit)  -> ***REDACTED-PHONE*** (no keep)
 *   R6  studentNo/realName JSON fields -> <prefix>"***REDACTED-PII***"
 *   R7  raw JWTs (aaa.bbb.ccc)         -> ***REDACTED-JWT*** (no keep)
 *
 * Output: redaction-manifest.json in the target dir with entries
 * {path, rule, count} ONLY - never matched values.
 *
 * Post-scan (fail closed): every processed file is re-scanned for LIVE
 * sensitive patterns (bearer/basic tokens, api-key values, cookie values,
 * JSON sensitive values, raw JWTs, emails, phones, studentNo/realName).
 * ANY residual hit => exit 2. Oversize/unscanned-binary files also force exit 2.
 * Exit codes: 0 clean | 2 residual/oversize/unscanned | 3 usage error.
 */
import fs from 'node:fs';
import path from 'node:path';

const target = process.argv[2];
if (!target) {
  console.error('usage: node redact-artifacts.mjs <targetDir>');
  process.exit(3);
}
let targetStat;
try {
  targetStat = fs.lstatSync(target);
} catch {
  console.error('target must be an existing directory: ' + target);
  process.exit(3);
}
if (!targetStat.isDirectory() || targetStat.isSymbolicLink()) {
  console.error('target must be a real local directory (symlinks are refused): ' + target);
  process.exit(3);
}

const TEXT_EXT = new Set([
  '.json', '.jsonl', '.md', '.txt', '.log', '.xml', '.properties',
  '.csv', '.tsv', '.har', '.html', '.htm', '.yaml', '.yml',
  '.ini', '.cfg', '.conf', '.eml', '.headers',
]);
// Content types that are deliberately not scanned; recorded, never auto-passed
// as redacted (screenshots always require manual visual review downstream).
const BINARY_EXT = new Set([
  '.png', '.jpg', '.jpeg', '.gif', '.webp', '.ico', '.bmp',
  '.pdf', '.zip', '.gz', '.tgz', '.jar', '.class',
  '.woff', '.woff2', '.ttf', '.eot',
  '.exe', '.dll', '.so', '.dylib', '.bin',
]);
const MAX_BYTES = 64 * 1024 * 1024;

// repl(m) receives the full match; trusted prefixes are taken from m's capture
// group 1 via the wrapper below. Secrets are consumed by the match and never
// re-emitted: every repl emits prefix + fixed marker only.
const rules = [
  { id: 'R1-authorization-bearer', re: /(Authorization\s*:\s*Bearer\s+)[A-Za-z0-9._\-]+/gi,
    repl: (m, p1) => p1 + '***REDACTED***' },
  { id: 'R1b-authorization-basic', re: /(Authorization\s*:\s*Basic\s+)[A-Za-z0-9+/=]+/gi,
    repl: (m, p1) => p1 + '***REDACTED***' },
  { id: 'R1c-api-key-header', re: /((?:x-)?api-key\s*:\s*)[A-Za-z0-9._\-]+/gi,
    repl: (m, p1) => p1 + '***REDACTED***' },
  { id: 'R2-cookie-header', re: /((?:Set-)?Cookie\s*:\s*)([^\r\n]+)/gi,
    repl: (m, p1) => p1 + '***REDACTED***' },
  { id: 'R3a-sensitive-json-field', re: /("(?:password|passwd|token|secret|authorization|apiKey|apikey|api_key)"\s*:\s*)"(?:[^"\\]|\\.)*"/gi,
    repl: (m, p1) => p1 + '"***"' },
  { id: 'R3b-sensitive-kv', re: /((?:password|passwd|token|secret|authorization|api_?key)=)[^\s&"']+/gi,
    repl: (m, p1) => p1 + '***' },
  { id: 'R4-email', re: /[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/g,
    repl: () => '***REDACTED-EMAIL***' },
  { id: 'R5-phone', re: /(?<!\d)(?:1[3-9]\d{9}|\d{11})(?!\d)/g,
    repl: () => '***REDACTED-PHONE***' },
  { id: 'R6-pii-fields', re: /("(?:studentNo|student_no|realName|real_name)"\s*:\s*)"(?:[^"\\]|\\.)*"/gi,
    repl: (m, p1) => p1 + '"***REDACTED-PII***"' },
  { id: 'R7-jwt', re: /\bey[A-Za-z0-9_-]{6,}\.[A-Za-z0-9_-]{6,}\.[A-Za-z0-9_-]{6,}\b/g,
    repl: () => '***REDACTED-JWT***' },
];

const RESIDUAL_CHECKS = [
  // No backtracking-dependent lookaheads: the marker begins with '*', so these
  // checks require the value's FIRST character to be a non-marker character.
  // Replaced text ("Bearer ***REDACTED***" / "Cookie: ***REDACTED***") can
  // therefore never match, while real values still do.
  { name: 'bearer-token', re: /Authorization\s*:\s*Bearer\s+[A-Za-z0-9][A-Za-z0-9._\-]{7,}/i },
  { name: 'basic-auth-header', re: /Authorization\s*:\s*Basic\s+[A-Za-z0-9+/=][A-Za-z0-9+/=]{7,}/i },
  { name: 'api-key-header', re: /\b(?:x-)?api-key\s*:\s*[A-Za-z0-9][A-Za-z0-9._\-]{7,}/i },
  { name: 'cookie-value', re: /(?:Set-)?Cookie\s*:\s*[^\s*][^\r\n]{5,}/i },
  { name: 'sensitive-json-value', re: /"(?:password|passwd|token|secret|authorization|apiKey|apikey|api_key)"\s*:\s*"(?!\*{3})[^"]+"/i },
  { name: 'sensitive-kv', re: /\b(?:password|token|secret|authorization|api_?key)=[^\s&"']{4,}/i },
  { name: 'jwt-token', re: /\bey[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b/ },
  { name: 'email', re: /[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/ },
  { name: 'phone', re: /(?<!\d)(?:1[3-9]\d{9}|\d{11})(?!\d)/ },
  { name: 'pii-fields', re: /"(?:studentNo|student_no|realName|real_name)"\s*:\s*"(?!\*\*\*REDACTED-PII\*\*\*")[^"]*"/i },
];

const unsafeLinks = [];
function* walk(dir) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isSymbolicLink()) {
      unsafeLinks.push(path.relative(target, full));
      continue;
    }
    if (entry.isDirectory()) yield* walk(full);
    else if (entry.isFile()) yield full;
  }
}

const manifest = [];
const residual = [];
const oversize = [];
const unscannedBinary = [];

for (const file of walk(target)) {
  const ext = path.extname(file).toLowerCase();
  const rel = path.relative(target, file);
  let text;
  if (!TEXT_EXT.has(ext)) {
    if (BINARY_EXT.has(ext)) {
      // Deliberately unscanned; recorded so nothing disappears from the
      // manifest silently. Screenshots additionally require manual review.
      manifest.push({ path: rel, rule: 'SKIPPED_BINARY', count: 0 });
      continue;
    }
    const st = fs.statSync(file);
    if (st.size > MAX_BYTES) {
      manifest.push({ path: rel, rule: 'OVERSIZE_UNREDACTED', count: 0 });
      oversize.push(rel);
      continue;
    }
    const buf = fs.readFileSync(file);
    if (buf.length > 0 && buf.subarray(0, 8192).includes(0)) {
      // FAIL CLOSED: binary content under an unlisted extension can never be
      // proven free of credentials (e.g. sqlite/cookie stores/keyrings).
      manifest.push({ path: rel, rule: 'UNSCANNED_BINARY_UNLISTED_EXT', count: 0 });
      unscannedBinary.push(rel);
      continue;
    }
    // Text-like content under an unlisted extension: redact it like text.
    text = buf.toString('utf8');
  } else {
    const st = fs.statSync(file);
    if (st.size > MAX_BYTES) {
      // FAIL CLOSED: an oversize text file cannot be proven redacted.
      manifest.push({ path: rel, rule: 'OVERSIZE_UNREDACTED', count: 0 });
      oversize.push(rel);
      continue;
    }
    text = fs.readFileSync(file, 'utf8');
  }
  const original = text;
  const perFile = [];
  for (const rule of rules) {
    let count = 0;
    text = text.replace(rule.re, (...args) => {
      count++;
      // args: [fullMatch, p1, p2?, offset, string]; group1 = trusted prefix.
      const p1 = args.length > 1 && args[1] !== undefined ? args[1] : '';
      return rule.repl(args[0], p1);
    });
    if (count > 0) perFile.push({ path: rel, rule: rule.id, count });
  }
  if (text !== original) fs.writeFileSync(file, text, 'utf8');
  manifest.push(...perFile);

  // Residual live-sensitive scan on the post-redaction bytes.
  const after = fs.readFileSync(file, 'utf8');
  for (const check of RESIDUAL_CHECKS) {
    if (check.re.test(after)) {
      residual.push({ file: rel, check: check.name });
    }
  }
}

if (unsafeLinks.length > 0) {
  for (const link of unsafeLinks) {
    residual.push({ file: link, check: 'UNSAFE-SYMLINK' });
  }
}

fs.writeFileSync(
  path.join(target, 'redaction-manifest.json'),
  JSON.stringify({
    generatedBy: 'T13 redact-artifacts.mjs',
    entries: manifest,
    oversizeUnredacted: oversize,
    unscannedBinary,
    unsafeLinks,
    residual,
  }, null, 2),
  'utf8'
);

const total = manifest.reduce((acc, e) => acc + e.count, 0);
console.log(`redaction: ${manifest.length} entries, ${total} replacements, oversize=${oversize.length}, unscannedBinary=${unscannedBinary.length}, residual=${residual.length}`);
if (oversize.length > 0) {
  for (const f of oversize) console.error(`OVERSIZE_UNREDACTED: ${f} (fail closed)`);
}
if (unscannedBinary.length > 0) {
  for (const f of unscannedBinary) console.error(`UNSCANNED_BINARY_UNLISTED_EXT: ${f} (fail closed)`);
}
if (residual.length > 0) {
  for (const r of residual) console.error(`RESIDUAL SENSITIVE PATTERN: ${r.check} in ${r.file}`);
}
if (oversize.length > 0 || unscannedBinary.length > 0 || residual.length > 0) process.exit(2);
process.exit(0);
