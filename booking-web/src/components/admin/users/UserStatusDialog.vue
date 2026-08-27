<template>
  <section v-if="target" class="confirm-region" role="dialog" aria-modal="true" aria-labelledby="user-status-title">
    <h2 id="user-status-title">确认{{ targetStatus === 1 ? '启用' : '禁用' }}</h2>
    <p>
      用户 <strong>{{ target.username }}</strong>（{{ target.realName || target.username }}）将被设为
      <strong>{{ targetStatus === 1 ? '启用' : '停用' }}</strong>。
    </p>
    <p v-if="selfDisable" class="warn" role="note">提示：这是当前登录管理员自己，后端将拒绝自禁用（409）。</p>
    <p v-if="opError" class="error" role="alert">{{ opError }}</p>
    <div class="actions">
      <button v-if="!armed" type="button" :disabled="loading" @click="arm">下一步：确认操作</button>
      <button v-else type="button" :disabled="loading" @click="submit">{{ loading ? '提交中…' : '确认执行' }}</button>
      <button type="button" :disabled="loading" @click="cancel">取消</button>
    </div>
  </section>
</template>

<script setup>
import { computed, ref, watch } from 'vue';

const props = defineProps({
  target: { type: Object, default: null },
  targetStatus: { type: Number, default: null },
  currentUserId: { type: String, default: '' },
  opState: { type: Object, default: null },
});

const emit = defineEmits(['confirmed', 'cancelled']);

const armed = ref(false);
watch(() => props.target, () => { armed.value = false; });

const loading = computed(() => props.opState?.phase === 'loading');
const opError = computed(() => (props.opState?.phase === 'error' ? props.opState.adminMessage : ''));
const selfDisable = computed(() => (
  !!props.currentUserId
  && !!props.target
  && String(props.target.id) === String(props.currentUserId)
  && props.targetStatus === 0
));

const arm = () => { armed.value = true; };
const cancel = () => { armed.value = false; emit('cancelled'); };
const submit = () => {
  if (!loading.value && armed.value) emit('confirmed', { id: props.target.id, status: props.targetStatus });
};
</script>

<style scoped>
.confirm-region { border: 1px solid #dcdfe6; padding: 16px; border-radius: 4px; margin-top: 12px; }
.actions { display: flex; gap: 8px; margin-top: 12px; }
.error { color: #c0392b; }
.warn { color: #b8860b; }
</style>
