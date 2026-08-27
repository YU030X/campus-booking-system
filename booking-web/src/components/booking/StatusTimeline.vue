<script setup>
import { computed } from 'vue';

const props = defineProps({
  booking: {
    type: Object,
    required: true,
  },
});

const labels = Object.freeze({
  PENDING_APPROVAL: '待审批',
  CONFIRMED: '已确认',
  CHECKED_IN: '已签到',
  COMPLETED: '已完成',
  REJECTED: '已驳回',
  CANCELLED: '已取消',
  NO_SHOW: '未到场',
});

const timestamp = computed(() => {
  if (props.booking.status === 'CHECKED_IN') return props.booking.checkinTime;
  if (props.booking.status === 'CANCELLED') return props.booking.cancelTime;
  return props.booking.updatedAt || props.booking.createdAt;
});
</script>

<template>
  <el-timeline aria-label="预约状态时间线">
    <el-timeline-item :timestamp="timestamp || ''" placement="top">
      {{ labels[booking.status] || booking.status }}
    </el-timeline-item>
  </el-timeline>
</template>
