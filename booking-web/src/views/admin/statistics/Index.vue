<template>
  <section class="statistics-page" aria-labelledby="statistics-title">
    <h1 id="statistics-title">运营统计</h1>
    <p v-if="!isAdmin" class="error" role="alert">仅 ADMIN 可访问统计。</p>
    <template v-else>
      <form class="filters" @submit.prevent="load" novalidate>
        <label>开始日期<input v-model="fromDate" type="date" required /></label>
        <label>结束日期<input v-model="toDate" type="date" required /></label>
        <button type="submit" :disabled="loading">{{ loading ? '查询中…' : '查询' }}</button>
      </form>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <p v-if="loading" role="status">正在加载统计…</p>

      <section aria-labelledby="resource-statistics-title">
        <h2 id="resource-statistics-title">资源使用情况</h2>
        <p v-if="!loading && !error && !resourceRecords.length" class="empty">该时段暂无资源使用数据。</p>
        <div v-if="resourceRecords.length" class="table-scroll">
          <table>
            <thead><tr><th>资源</th><th>预约</th><th>完成</th><th>取消</th><th>未到</th><th>占用分钟</th><th>使用率</th></tr></thead>
            <tbody><tr v-for="item in resourceRecords" :key="item.resourceId">
              <td>{{ item.resourceName }}（{{ item.resourceId }}）</td>
              <td>{{ item.bookingCount }}</td><td>{{ item.completedCount }}</td>
              <td>{{ item.cancelledCount }}</td><td>{{ item.noShowCount }}</td>
              <td>{{ item.occupiedSlotMinutes }}</td><td>{{ formatRate(item.usageRate) }}</td>
            </tr></tbody>
          </table>
        </div>
      </section>

      <section aria-labelledby="booking-statistics-title">
        <h2 id="booking-statistics-title">预约状态分布</h2>
        <p v-if="!loading && !error && !bookingRecords.length" class="empty">该时段暂无预约数据。</p>
        <table v-if="bookingRecords.length">
          <thead><tr><th>状态</th><th>数量</th></tr></thead>
          <tbody><tr v-for="item in bookingRecords" :key="item.status">
            <td>{{ item.status }}</td><td>{{ item.count }}</td>
          </tr></tbody>
        </table>
      </section>
    </template>
  </section>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue';
import { supportApi, requireDate } from '../../../api/support.js';
import { useAuthStore } from '../../../stores/auth';

const auth = useAuthStore();
const isAdmin = computed(() => auth.role === 'ADMIN');
const fromDate = ref(localDate(-29));
const toDate = ref(localDate(0));
const resourceRecords = ref([]);
const bookingRecords = ref([]);
const loading = ref(false);
const error = ref('');

function localDate(dayOffset) {
  const date = new Date();
  date.setHours(12, 0, 0, 0);
  date.setDate(date.getDate() + dayOffset);
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

function validateRange() {
  requireDate(fromDate.value, 'fromDate');
  requireDate(toDate.value, 'toDate');
  if (fromDate.value > toDate.value) throw new RangeError('开始日期不能晚于结束日期');
  const days = (Date.parse(`${toDate.value}T00:00:00Z`) - Date.parse(`${fromDate.value}T00:00:00Z`)) / 86400000;
  if (days > 365) throw new RangeError('统计范围最多 366 天');
}

async function load() {
  if (!isAdmin.value) return;
  error.value = '';
  try {
    validateRange();
  } catch (failure) {
    error.value = failure.message;
    return;
  }
  loading.value = true;
  try {
    const [resources, bookings] = await Promise.all([
      supportApi.resourceStatistics(fromDate.value, toDate.value),
      supportApi.bookingStatistics(fromDate.value, toDate.value),
    ]);
    resourceRecords.value = resources.records;
    bookingRecords.value = bookings.records;
  } catch (failure) {
    error.value = failure.supportMessage || '统计加载失败。';
    resourceRecords.value = [];
    bookingRecords.value = [];
  } finally {
    loading.value = false;
  }
}

function formatRate(value) {
  return value === null ? '—' : `${(Number(value) * 100).toFixed(1)}%`;
}

onMounted(async () => {
  await auth.ensureHydrated();
  if (isAdmin.value) await load();
});
</script>

<style scoped>
.statistics-page { max-width: 70rem; margin: 2rem auto; padding: 1.5rem; }
.filters { display: flex; flex-wrap: wrap; align-items: end; gap: .75rem; margin-bottom: 1.5rem; }
.filters label { display: grid; gap: .25rem; }
input, button { font: inherit; min-height: 2.2rem; padding: .4rem; }
section section { margin-top: 1.5rem; }
.table-scroll { overflow-x: auto; }
table { width: 100%; border-collapse: collapse; }
th, td { border-bottom: 1px solid #d0d5dd; padding: .55rem; text-align: left; white-space: nowrap; }
.error { color: #b42318; }
.empty { color: #667085; }
</style>
