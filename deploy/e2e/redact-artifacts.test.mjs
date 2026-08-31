import test from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const script = fileURLToPath(new URL('./redact-artifacts.mjs', import.meta.url));

test('redacts credentials and PII and leaves a clean manifest', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 't13-redactor-'));
  try {
    const artifact = path.join(directory, 'network.log');
    fs.writeFileSync(artifact, [
      'Authorization: Bearer abcdefghijklmnop',
      'Cookie: session=super-secret-cookie',
      '{"password":"open-sesame","token":"jwt-value","realName":"Test Person","studentNo":"20260001"}',
      'user@example.test 13812345678',
    ].join('\n'));

    execFileSync(process.execPath, [script, directory], { stdio: 'pipe' });

    const redacted = fs.readFileSync(artifact, 'utf8');
    for (const secret of ['abcdefghijklmnop', 'super-secret-cookie', 'open-sesame', 'jwt-value', 'Test Person', '20260001', 'user@example.test', '13812345678']) {
      assert.equal(redacted.includes(secret), false, `secret remained: ${secret}`);
    }
    assert.match(redacted, /\*\*\*REDACTED/);
    const manifest = JSON.parse(fs.readFileSync(path.join(directory, 'redaction-manifest.json'), 'utf8'));
    assert.deepEqual(manifest.residual, []);
    assert.deepEqual(manifest.oversizeUnredacted, []);
    assert.ok(manifest.entries.length >= 6);
  } finally {
    fs.rmSync(directory, { recursive: true, force: true });
  }
});
