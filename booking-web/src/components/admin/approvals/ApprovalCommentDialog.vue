<template>
  <section v-if="target" class="confirm-region" role="dialog" aria-modal="true" aria-labelledby="approval-action-title">
    <h2 id="approval-action-title">{{ isReject ? '驳回预约' : '批准预约' }}</h2>
    <p>
      预约 <strong>{{ target.bookingNo }}</strong>（ID {{ target.id }}）将被
      <strong>{{ isReject ? '驳回' : '批准' }}</strong>。
    </p>
    <label>
      {{ isReject ? '驳回备注（必填）' : '批准备注（可选）' }}
      <textarea
        v-model="comment"
        rows="3"
        :placeholder="isReject ? '请输入驳回原因' : '可填写说明，留空则不发送备注'"
      ></textarea>
      <span class="count">{{ codePoints }}/500</span>
      <p v-if="validationError" class="error" role="alert">{{ validationError }}</p>
    </label>
    <p v-if="opError" class="error" role="alert">{{ opError }}</p>
    <div class="actions">
      <button
        v-if="!armed"
        type="button"
        :disabled="loading || !!validationError"
        @click="arm"
      >
        下一步：确认{{ isReject ? '驳回' : '批准' }}
      </button>
      <button v-else type="button" :disabled="loading" @click="submit">
        {{ loading ? '提交中…' : `确认${isReject ? '驳回' : '批准'}` }}
      </button>
      <button type="button" :disabled="loading" @click="cancel">取消</button>
    </div>
  </section>
</template>

<script setup>
import { computed, ref, watch } from 'vue';
import { normalizeApproveComment, validateRejectComment, codePointLength } from '../../../api/adminApprovals';

const props = defineProps({
  target: { type: Object, default: null },
  action: { type: String, default: '' },
  currentOpState: { type: Object, default: null },
});

const emit = defineEmits(['confirmed', 'cancelled']);

const comment = ref('');
const armed = ref(false);
watch(() => [props.target?.id, props.action], () => {
  armed.value = false;
  comment.value = '';
});

const isReject = computed(() => props.action === 'reject');
const loading = computed(() => props.currentOpState?.phase === 'loading');
const opError = computed(() => (props.currentOpState?.phase === 'error' ? props.currentOpState.adminMessage : ''));

const validationError = computed(() => {
  if (!isReject.value) {
    try {
      normalizeApproveComment(comment.value);
      return '';
    } catch (error) {
      return error.message;
    }
  }
  try {
    validateRejectComment(comment.value);
    return '';
  } catch (error) {
    return error.message;
  }
});

const codePoints = computed(() => codePointLength(comment.value));

function arm() {
  if (!validationError.value) armed.value = true;
}
function cancel() {
  armed.value = false;
  emit('cancelled');
}
function submit() {
  if (!armed.value || loading.value || validationError.value) return;
  emit('confirmed', {
    id: props.target.id,
    action: props.action,
    comment: comment.value,
  });
}
</script>

<style scoped>
.confirm-region { border: 1px solid #dcdfe6; padding: 16px; border-radius: 4px; margin-top: 12px; }
textarea { width: 100%; margin-top: 4px; }
.count { color: #7f8c8d; font-size: 12px; }
.actions { display: flex; gap: 8px; margin-top: 12px; }
.error { color: #c0392b; }
</style>
