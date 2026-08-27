import test from 'node:test';
import assert from 'node:assert/strict';
import {
  normalizeApproveComment,
  validateRejectComment,
  codePointLength,
  canActOn,
} from '../../src/api/adminApprovals.js';

test('approve comment trims and maps blank to null', () => {
  assert.equal(normalizeApproveComment('  处理妥当  '), '处理妥当');
  assert.equal(normalizeApproveComment('   \t\n '), null);
  assert.equal(normalizeApproveComment(''), null);
});

test('approve comment enforces a 500 Unicode code point ceiling', () => {
  const exactly = 'あ'.repeat(500);
  assert.equal(normalizeApproveComment(` ${exactly} `), exactly);
  assert.throws(() => normalizeApproveComment('あ'.repeat(501)), /500/);
});

test('code point counting counts astral pairs once', () => {
  assert.equal(codePointLength('😀'.repeat(251)), 251);
  assert.throws(() => normalizeApproveComment('😀'.repeat(251)), /500/, '502 code points must be rejected');
  assert.doesNotThrow(() => normalizeApproveComment('😀'.repeat(250)), '500 code points must pass');
  assert.equal('😀'.repeat(251).length, 502, 'utf-16 length would wrongly allow astral flooding');
});

test('reject comment requires 1..500 trimmed code points', () => {
  assert.equal(validateRejectComment(' 材料不足 '), '材料不足');
  assert.throws(() => validateRejectComment('   '), /必填/);
  assert.throws(() => validateRejectComment(''), /必填/);
  assert.throws(() => validateRejectComment(null), /必填/);
  assert.throws(() => validateRejectComment('あ'.repeat(501)), /500/);
  assert.equal(validateRejectComment('a'.repeat(500)).length, 500);
});

test('pending controls derive only from server status', () => {
  assert.ok(canActOn({ status: 'PENDING_APPROVAL' }));
  for (const status of ['APPROVED', 'REJECTED', 'CANCELLED', 'COMPLETED', 'NO_SHOW']) {
    assert.equal(canActOn({ status }), false, `${status} must be read-only`);
    assert.equal(canActOn(null), false);
  }
});
