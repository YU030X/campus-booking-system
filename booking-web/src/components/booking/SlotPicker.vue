<script setup>
import { isPastSlot } from './slots';

const props = defineProps({
  date: { type: String, required: true },
  slots: { type: Array, default: () => [] },
  selected: { type: Array, default: () => [] },
  nowText: { type: String, default: '' },
});

const emit = defineEmits(['toggle']);
const isPast = (slot) => isPastSlot(props.date, slot.startTime, props.nowText);
const isDisabled = (slot) => !slot.available || isPast(slot);
const isSelected = (slot) => props.selected.includes(slot.startTime);
const toggle = (slot) => {
  if (!isDisabled(slot)) emit('toggle', slot);
};
</script>

<template>
  <div class="slot-picker" role="group" aria-label="可用时段">
    <button
      v-for="slot in slots"
      :key="slot.startTime"
      type="button"
      :disabled="isDisabled(slot)"
      :aria-pressed="isSelected(slot)"
      :class="{ selected: isSelected(slot) }"
      @click="toggle(slot)"
    >
      {{ slot.startTime }}–{{ slot.endTime }}
      <span v-if="isPast(slot)">（已过期）</span>
      <span v-else-if="!slot.available">（不可用）</span>
    </button>
    <p v-if="slots.length === 0">当天暂无可预约时段</p>
  </div>
</template>

<style scoped>
.slot-picker { display: flex; flex-wrap: wrap; gap: 0.5rem; }
.slot-picker button { padding: 0.5rem 0.75rem; }
.slot-picker button.selected { color: white; background: #409eff; }
.slot-picker p { width: 100%; }
</style>
