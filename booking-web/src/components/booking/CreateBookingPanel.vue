<script setup>
import { computed, onUnmounted, ref, watch } from 'vue';
import { useBookingStore } from '../../stores/booking';
import { isResourceId, isValidDate, normalizePurpose } from './validation';
import { buildSelection, isPastSlot } from './slots';
import SlotPicker from './SlotPicker.vue';

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  resourceId: { type: String, default: '' },
  date: { type: String, default: '' },
});

const emit = defineEmits(['update:modelValue', 'success']);
const store = useBookingStore();
const selectedTimes = ref([]);
const purpose = ref('');
const attendeeCount = ref(1);
const selectionError = ref('');
const fieldError = ref('');

const shanghaiNow = () => {
  const parts = Object.fromEntries(new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
  }).formatToParts(new Date()).filter((part) => part.type !== 'literal').map((part) => [part.type, part.value]));
  return `${parts.year}-${parts.month}-${parts.day} ${parts.hour}:${parts.minute}:${parts.second}`;
};
const nowText = ref(shanghaiNow());
let clock = null;
const startClock = () => {
  if (clock) return;
  nowText.value = shanghaiNow();
  clock = setInterval(() => { nowText.value = shanghaiNow(); }, 30_000);
};
const stopClock = () => {
  if (clock) clearInterval(clock);
  clock = null;
};

const validScope = computed(() => isResourceId(props.resourceId) && isValidDate(props.date));
const slots = computed(() => store.availability.data?.slots || []);
const selectedSlots = computed(() => slots.value.filter((slot) => selectedTimes.value.includes(slot.startTime)));
const selection = computed(() => buildSelection(props.date, selectedSlots.value));
const durationText = computed(() => selection.value.valid ? `${selection.value.value.durationMinutes} 分钟` : '未选择');
const loading = computed(() => store.availability.status === 'loading');
const submitting = computed(() => store.create.pending);
const availabilityError = computed(() => store.availability.status === 'error'
  ? (store.availability.error?.userMessage || '可用时段加载失败') : '');
const submitError = computed(() => store.create.status === 'error'
  ? (store.create.error?.userMessage || '预约提交失败') : '');

const load = () => {
  if (!validScope.value) return Promise.resolve();
  return store.fetchAvailability(props.resourceId, props.date, { force: true }).catch(() => {});
};

const toggle = (slot) => {
  selectionError.value = '';
  const current = selectedTimes.value;
  const candidate = current.includes(slot.startTime)
    ? current.filter((time) => time !== slot.startTime)
    : [...current, slot.startTime];
  const candidateSlots = slots.value.filter((item) => candidate.includes(item.startTime));
  if (candidateSlots.length > 0) {
    const result = buildSelection(props.date, candidateSlots);
    if (!result.valid) {
      selectionError.value = result.errors[0];
      return;
    }
  }
  selectedTimes.value = candidate;
};

const close = () => {
  if (submitting.value) return;
  emit('update:modelValue', false);
};

const submit = async () => {
  if (submitting.value) return;
  fieldError.value = '';
  if (!selection.value.valid) {
    fieldError.value = selection.value.errors[0];
    return;
  }
  nowText.value = shanghaiNow();
  if (selectedSlots.value.some((slot) => isPastSlot(props.date, slot.startTime, nowText.value))) {
    fieldError.value = '所选时段已经开始，请重新选择';
    return;
  }
  const normalizedPurpose = normalizePurpose(purpose.value);
  if (normalizedPurpose.error) {
    fieldError.value = normalizedPurpose.error;
    return;
  }
  if (!Number.isInteger(attendeeCount.value) || attendeeCount.value < 1) {
    fieldError.value = '参与人数必须是不小于 1 的整数';
    return;
  }
  try {
    const booking = await store.createBooking({
      resourceId: props.resourceId,
      startTime: selection.value.value.startTime,
      endTime: selection.value.value.endTime,
      purpose: normalizedPurpose.value,
      attendeeCount: attendeeCount.value,
    });
    selectedTimes.value = [];
    purpose.value = '';
    attendeeCount.value = 1;
    emit('success', booking);
    emit('update:modelValue', false);
  } catch {
    // Store exposes canonical user-facing failure state.
  }
};

watch(
  () => [props.modelValue, props.resourceId, props.date],
  ([open]) => {
    if (!open) {
      stopClock();
      return;
    }
    startClock();
    selectedTimes.value = [];
    selectionError.value = '';
    fieldError.value = '';
    store.clearCreateState();
    load();
  },
  { immediate: true },
);
onUnmounted(stopClock);
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    title="创建预约"
    size="520px"
    @close="close"
  >
    <p v-if="!validScope" role="alert">资源编号或日期无效，无法创建预约。</p>
    <template v-else>
      <p>资源编号：{{ resourceId }}</p>
      <p>预约日期：{{ date }}</p>
      <p v-if="loading" aria-live="polite">可用时段加载中…</p>
      <p v-else-if="availabilityError" role="alert">
        {{ availabilityError }}
        <button type="button" @click="load">重试</button>
      </p>
      <SlotPicker
        v-else
        :date="date"
        :slots="slots"
        :selected="selectedTimes"
        :now-text="nowText"
        @toggle="toggle"
      />
      <p v-if="selectionError" role="alert">{{ selectionError }}</p>
      <p>时长：{{ durationText }}</p>
      <label>
        用途
        <textarea v-model="purpose" rows="3" placeholder="可选，最多 500 个字符" />
      </label>
      <label>
        参与人数
        <input v-model.number="attendeeCount" type="number" min="1" step="1" />
      </label>
      <p v-if="fieldError || submitError" role="alert">{{ fieldError || submitError }}</p>
      <div class="actions">
        <el-button :disabled="submitting" @click="close">取消</el-button>
        <el-button type="primary" :loading="submitting" :disabled="submitting" @click="submit">
          提交预约
        </el-button>
      </div>
    </template>
  </el-drawer>
</template>

<style scoped>
label { display: grid; gap: 0.25rem; margin-top: 1rem; }
.actions { display: flex; justify-content: flex-end; gap: 0.5rem; margin-top: 1.5rem; }
</style>
