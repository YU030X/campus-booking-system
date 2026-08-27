<script setup>
import { computed, watch } from 'vue';
import { useRoute } from 'vue-router';
import { isBookingId } from '../../components/booking/validation';
import { useBookingStore } from '../../stores/booking';
import CancelBookingButton from '../../components/booking/CancelBookingButton.vue';
import StatusTimeline from '../../components/booking/StatusTimeline.vue';

const route = useRoute();
const store = useBookingStore();
const id = computed(() => typeof route.params.id === 'string' ? route.params.id : '');
const unsafeId = computed(() => !isBookingId(id.value));
const requestNotFound = computed(() => store.detail.status === 'error'
  && store.detail.error?.kind === 'NOT_FOUND');
const notFound = computed(() => unsafeId.value || requestNotFound.value);
const booking = computed(() => store.detail.data);
const loading = computed(() => !notFound.value && store.detail.status === 'loading');
const errorMessage = computed(() => !notFound.value && store.detail.status === 'error'
  ? (store.detail.error?.userMessage || '预约详情加载失败') : '');

const labels = Object.freeze({
  PENDING_APPROVAL: '待审批', CONFIRMED: '已确认', CHECKED_IN: '已签到',
  COMPLETED: '已完成', REJECTED: '已驳回', CANCELLED: '已取消', NO_SHOW: '未到场',
});

const load = () => {
  if (unsafeId.value) return;
  store.fetchDetail(id.value, { force: true }).catch(() => {});
};

watch(id, load, { immediate: true });
</script>

<template>
  <main aria-labelledby="booking-detail-title">
    <p v-if="notFound" role="alert">预约不存在</p>
    <p v-else-if="loading" aria-live="polite">加载中…</p>
    <p v-else-if="errorMessage" role="alert">
      {{ errorMessage }} <button type="button" @click="load">重试</button>
    </p>
    <article v-else-if="booking">
      <h1 id="booking-detail-title">预约详情</h1>
      <el-descriptions :column="1" border>
        <el-descriptions-item label="预约 ID">{{ booking.id }}</el-descriptions-item>
        <el-descriptions-item label="预约号">{{ booking.bookingNo }}</el-descriptions-item>
        <el-descriptions-item label="用户 ID">{{ booking.userId }}</el-descriptions-item>
        <el-descriptions-item label="资源 ID">{{ booking.resourceId }}</el-descriptions-item>
        <el-descriptions-item label="开始时间">{{ booking.startTime }}</el-descriptions-item>
        <el-descriptions-item label="结束时间">{{ booking.endTime }}</el-descriptions-item>
        <el-descriptions-item label="用途">{{ booking.purpose || '—' }}</el-descriptions-item>
        <el-descriptions-item label="参与人数">{{ booking.attendeeCount }}</el-descriptions-item>
        <el-descriptions-item label="状态"><el-tag>{{ labels[booking.status] }}</el-tag></el-descriptions-item>
        <el-descriptions-item label="签到时间">{{ booking.checkinTime || '—' }}</el-descriptions-item>
        <el-descriptions-item label="取消时间">{{ booking.cancelTime || '—' }}</el-descriptions-item>
        <el-descriptions-item label="取消原因">{{ booking.cancelReason || '—' }}</el-descriptions-item>
        <el-descriptions-item label="创建时间">{{ booking.createdAt }}</el-descriptions-item>
        <el-descriptions-item label="更新时间">{{ booking.updatedAt }}</el-descriptions-item>
      </el-descriptions>
      <h2>当前状态</h2>
      <StatusTimeline :booking="booking" />
      <CancelBookingButton :booking="booking" />
    </article>
  </main>
</template>
