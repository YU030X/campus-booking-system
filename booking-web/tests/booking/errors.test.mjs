import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import {
  KINDS,
  SLOT_CONFLICT_BACKEND_MESSAGE,
  SYSTEM_BUSY_BACKEND_MESSAGE,
  classifyBookingError,
} from '../../src/components/booking/errors.js';

const transportError = (status, body) => ({
  response: { status, data: body },
});

describe('409/43000 联合判定(status+code+message)', () => {
  it('SLOT_CONFLICT 精确消息映射并要求刷新时段', () => {
    const result = classifyBookingError(transportError(409, { code: 43000, message: SLOT_CONFLICT_BACKEND_MESSAGE }));
    assert.equal(result.kind, KINDS.SLOT_CONFLICT);
    assert.equal(result.userMessage, '该时段刚被其他人预约，请刷新');
    assert.equal(result.refreshSlots, true);
  });
  it('SYSTEM_BUSY 精确消息映射为系统繁忙且不声称时段被占', () => {
    const result = classifyBookingError(transportError(409, { code: 43000, message: SYSTEM_BUSY_BACKEND_MESSAGE }));
    assert.equal(result.kind, KINDS.SYSTEM_BUSY);
    assert.equal(result.refreshSlots, false);
    assert.doesNotMatch(result.userMessage, /被占用|被别人预约/);
    assert.match(result.userMessage, /稍后重试/);
  });
  it('仅凭 code 43000 不能落入 SLOT_CONFLICT 分支', () => {
    const result = classifyBookingError(transportError(409, { code: 43000, message: 'some other booking error' }));
    assert.notEqual(result.kind, KINDS.SLOT_CONFLICT);
    assert.notEqual(result.kind, KINDS.SYSTEM_BUSY);
    assert.equal(result.kind, KINDS.CONFLICT);
    assert.equal(result.refreshSlots, false);
  });
  it('message 相同但 status/code 不符时不映射为冲突细分', () => {
    const wrongStatus = classifyBookingError(transportError(500, { code: 43000, message: SLOT_CONFLICT_BACKEND_MESSAGE }));
    assert.notEqual(wrongStatus.kind, KINDS.SLOT_CONFLICT);
    const wrongCode = classifyBookingError(transportError(409, { code: 42000, message: SLOT_CONFLICT_BACKEND_MESSAGE }));
    assert.notEqual(wrongCode.kind, KINDS.SLOT_CONFLICT);
  });
});

describe('HTTP 状态映射', () => {
  it('401 归类为会话过期(由共享 handler 清理)', () => {
    const result = classifyBookingError(transportError(401, { code: 40100, message: 'unauthenticated' }));
    assert.equal(result.kind, KINDS.AUTH_EXPIRED);
  });
  it('403 归类为无权限且保留会话(sessionAction 为 null)', () => {
    const result = classifyBookingError(transportError(403, { code: 40300, message: 'forbidden' }));
    assert.equal(result.kind, KINDS.FORBIDDEN);
    assert.equal(result.sessionAction, null);
  });
  it('404 归类为不存在(归属掩码统一)', () => {
    const result = classifyBookingError(transportError(404, { code: 40400, message: 'booking not found' }));
    assert.equal(result.kind, KINDS.NOT_FOUND);
  });
  it('400/40000 归类为参数无效', () => {
    const byStatus = classifyBookingError(transportError(400, { code: 40000, message: 'invalid parameter' }));
    assert.equal(byStatus.kind, KINDS.INVALID_INPUT);
  });
  it('未知错误保持失败态,永不转换为成功', () => {
    const result = classifyBookingError(transportError(500, { code: 50000, message: 'internal server error' }));
    assert.equal(result.kind, KINDS.UNKNOWN);
    const empty = classifyBookingError(new Error('network down'));
    assert.equal(empty.kind, KINDS.UNKNOWN);
    assert.ok(empty.userMessage.length > 0);
  });
});
