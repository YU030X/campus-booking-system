#!/usr/bin/env node
/**
 * T13 slice 4 - offline redaction for T13 E2E artifact TEXT files.
 *
 * Scope guard: processes ONLY the directory given as argv[2] (recursive), ONLY
 * text-ish extensions (.json .jsonl .md .txt .log .xml .properties). Files over
 * 64MB CANNOT be safely redacted by this bounded reader: they are recorded as
 * OVERSIZE_UNREDACTED and force a FAIL-CLOSED exit 2 (no silent skip-then-pass).
 * Never touches files outside the target directory - T08 originals are copied
 * first by run.ps1.
 *
 * Rules (replacement keeps ONLY capture group 1 - the trusted prefix - and
 * appends a marker; the matched secret itself is never re-emitted):
 *   R1 Authorization: Bearer <token>   -> <prefix>***REDACTED***
 *   R2 (Set-)Cookie header values      -> <prefix>***REDACTED***
 *   R3a JSON sensitive fields          -> <prefix>"***"
 *   R3b key=value sensitive fields     -> <prefix>***
 *   R4 email addresses                 -> ***REDACTED-EMAIL*** (no keep)
 *   R5 phones (CN mobile / 11-digit)   -> ***REDACTED-PHONE*** (no keep)
 *   R6 studentNo/realName JSON fields  -> <prefix>"***REDACTED-PII***"
 *
 * Output: redaction-manifest.json in the target dir with entries
 * {path, rule, count} ONLY - never matched values.
 *
 * Post-scan (fail closed): every processed file is re-scanned for LIVE
 * sensitive patterns (bearer tokens, cookie values, JSON sensitive values,
 * emails, phones, studentNo/realName). ANY residual hit => exit 2.
 * Oversize-unredacted files also force exit 2.
 * Exit codes: 0 clean | 2 residual/oversize | 3 usage error.
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

const TEXT_EXT = new Set(['.json', '.jsonl', '.md', '.txt', '.log', '.xml', '.properties']);
const MAX_BYTES = 64 * 1024 * 1024;

// repl(m) receives the full match; trusted prefixes are taken from m's capture
// group 1 via the wrapper below. Secrets are consumed by the match and never
// re-emitted: every repl emits prefix + fixed marker only.
const rules = [
  { id: 'R1-authorization-bearer', re: /(Authorization\s*:\s*Bearer\s+)[A-Za-z0-9._\-]+/gi,
    repl: (m, p1) => p1 + '***REDACTED***' },
  { id: 'R2-cookie-header', re: /((?:Set-)?Cookie\s*:\s*)([^\r\n]+)/gi,
    repl: (m, p1) => p1 + '***REDACTED***' },
  { id: 'R3a-sensitive-json-field', re: /("(?:password|passwd|token|secret|authorization|apiKey)"\s*:\s*)"(?:[^"\\]|\\.)*"/gi,
    repl: (m, p1) => p1 + '"***"' },
  { id: 'R3b-sensitive-kv', re: /((?:password|passwd|token|secret|authorization)=)[^\s&"']+/gi,
    repl: (m, p1) => p1 + '***' },
  { id: 'R4-email', re: /[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/g,
    repl: () => '***REDACTED-EMAIL***' },
  { id: 'R5-phone', re: /(?<!\d)(?:1[3-9]\d{9}|\d{11})(?!\d)/g,
    repl: () => '***REDACTED-PHONE***' },
  { id: 'R6-pii-fields', re: /("(?:studentNo|student_no|realName|real_name)"\s*:\s*)"(?:[^"\\]|\\.)*"/gi,
    repl: (m, p1) => p1 + '"***REDACTED-PII***"' },
];

const RESIDUAL_CHECKS = [
  // No backtracking-dependent lookaheads: the marker begins with '*', so these
  // checks require the value's FIRST character to be a non-marker character.
  // Replaced text ("Bearer ***REDACTED***" / "Cookie: ***REDACTED***") can
  // therefore never match, while real values still do.
  { name: 'bearer-token', re: /Authorization\s*:\s*Bearer\s+[A-Za-z0-9][A-Za-z0-9._\-]{7,}/i },
  { name: 'cookie-value', re: /(?:Set-)?Cookie\s*:\s*[^\s*][^\r\n]{5,}/i },
  { name: 'sensitive-json-value', re: /"(?:password|passwd|token|secret|authorization|apiKey)"\s*:\s*"(?!\*{3})[^"]+"/i },
  { name: 'sensitive-kv', re: /\b(?:password|token|secret|authorization)=[^\s&"']{4,}/i },
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

for (const file of walk(target)) {
  const ext = path.extname(file).toLowerCase();
  if (!TEXT_EXT.has(ext)) continue;
  const st = fs.statSync(file);
  if (st.size > MAX_BYTES) {
    // FAIL CLOSED: an oversize text file cannot be proven redacted.
    const rel = path.relative(target, file);
    manifest.push({ path: rel, rule: 'OVERSIZE_UNREDACTED', count: 0 });
    oversize.push(rel);
    continue;
  }
  let text = fs.readFileSync(file, 'utf8');
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
    if (count > 0) perFile.push({ path: path.relative(target, file), rule: rule.id, count });
  }
  if (text !== original) fs.writeFileSync(file, text, 'utf8');
  manifest.push(...perFile);

  // Residual live-sensitive scan on the post-redaction bytes.
  const after = fs.readFileSync(file, 'utf8');
  for (const check of RESIDUAL_CHECKS) {
    if (check.re.test(after)) {
      residual.push({ file: path.relative(target, file), check: check.name });
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
    unsafeLinks,
    residual,
  }, null, 2),
  'utf8'
);

const total = manifest.reduce((acc, e) => acc + e.count, 0);
console.log(`redaction: ${manifest.length} entries, ${total} replacements, oversize=${oversize.length}, residual=${residual.length}`);
if (oversize.length > 0) {
  for (const f of oversize) console.error(`OVERSIZE_UNREDACTED: ${f} (fail closed)`);
}
if (residual.length > 0) {
  for (const r of residual) console.error(`RESIDUAL SENSITIVE PATTERN: ${r.check} in ${r.file}`);
}
if (oversize.length > 0 || residual.length > 0) process.exit(2);
process.exit(0);
