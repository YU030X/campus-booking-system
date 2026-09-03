<script setup>
import { computed, onUnmounted, ref } from 'vue';
import { useBookingStore } from '../../stores/booking';
import { normalizeCancelReason } from './validation';

const props = defineProps({
  booking: { type: Object, required: true },
});

const emit = defineEmits(['success']);
const store = useBookingStore();
const open = ref(false);
const reason = ref('');
const fieldError = ref('');
const pending = computed(() => store.cancel.pending);
const requestError = computed(() => store.cancel.status === 'error'
  ? (store.cancel.error?.userMessage || '取消预约失败') : '');
const nowInShanghai = () => {
  const parts = Object.fromEntries(new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
  }).formatToParts(new Date()).filter((part) => part.type !== 'literal').map((part) => [part.type, part.value]));
  return `${parts.year}-${parts.month}-${parts.day} ${parts.hour}:${parts.minute}:${parts.second}`;
};
const nowText = ref(nowInShanghai());
const clock = setInterval(() => { nowText.value = nowInShanghai(); }, 30_000);
onUnmounted(() => clearInterval(clock));
const cancellable = computed(() => ['PENDING_APPROVAL', 'CONFIRMED'].includes(props.booking.status)
  && props.booking.startTime > nowText.value);

const show = () => {
  reason.value = '';
  fieldError.value = '';
  open.value = true;
};

const submit = async () => {
  if (pending.value) return;
  const normalized = normalizeCancelReason(reason.value);
  if (normalized.error) {
    fieldError.value = normalized.error;
    return;
  }
  fieldError.value = '';
  try {
    const result = await store.cancelBooking(props.booking.id, normalized.value);
    open.value = false;
    emit('success', result);
  } catch {
    // Store retains canonical 401/403/404/409 mapping for rendering.
  }
};
</script>

<template>
  <el-button v-if="cancellable" type="danger" plain @click="show">取消预约</el-button>
  <el-button v-else disabled>当前状态不可取消</el-button>
  <el-dialog v-model="open" title="取消预约" width="460px" :close-on-click-modal="!pending">
    <label>
      取消原因
      <textarea v-model="reason" rows="3" placeholder="可选，最多 200 个字符" />
    </label>
    <p v-if="fieldError || requestError" role="alert">{{ fieldError || requestError }}</p>
    <template #footer>
      <el-button :disabled="pending" @click="open = false">返回</el-button>
      <el-button type="danger" :loading="pending" :disabled="pending" @click="submit">确认取消</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
label { display: grid; gap: 0.25rem; }
</style>
