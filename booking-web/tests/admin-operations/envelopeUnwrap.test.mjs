import test from 'node:test';
import assert from 'node:assert/strict';
import { unwrapResult } from '../../src/api/adminEnvelope.js';

const envelope = (body, status = 200) => ({ status, data: body });

test('successful envelopes unwrap to their data payload', () => {
  const payload = { records: [], pageNumber: 1, pageSize: 10, total: 0 };
  assert.strictEqual(unwrapResult(envelope({ code: 0, message: 'ok', data: payload })), payload);
  const nullData = unwrapResult(envelope({ code: 0, message: 'ok', data: null }));
  assert.equal(nullData, null);
});

test('non-zero business codes reject with annotated errors', () => {
  try {
    unwrapResult(envelope({ code: 43000, message: 'illegal transition', data: null }, 409));
    assert.fail('must throw');
  } catch (error) {
    assert.equal(error.code, 43000);
    assert.match(error.message, /illegal transition/);
    assert.equal(error.response.status, 409);
  }
});

test('malformed bodies without a canonical envelope are rejected', () => {
  for (const bad of [undefined, null, 'text', [1, 2], {}]) {
    assert.throws(() => unwrapResult(envelope(bad)), /canonical|code/);
  }
});
