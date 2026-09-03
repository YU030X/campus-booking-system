<script setup>
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { BOOKING_STATUS } from '../../types/contracts';
import { isResourceId, isValidDate, parseBookingHandoff } from '../../components/booking/validation';
import { useBookingStore } from '../../stores/booking';
import CancelBookingButton from '../../components/booking/CancelBookingButton.vue';
import CreateBookingPanel from '../../components/booking/CreateBookingPanel.vue';

const route = useRoute();
const store = useBookingStore();
const pageNumber = ref(1);
const pageSize = ref(10);
const status = ref('');
const createOpen = ref(false);
const resourceId = ref('');
const bookingDate = ref('');
const handoffError = ref('');

const statusLabels = Object.freeze({
  PENDING_APPROVAL: '待审批', CONFIRMED: '已确认', CHECKED_IN: '已签到',
  COMPLETED: '已完成', REJECTED: '已驳回', CANCELLED: '已取消', NO_SHOW: '未到场',
});
const records = computed(() => store.pageResult.records);
const total = computed(() => store.pageResult.total);
const loading = computed(() => store.list.status === 'loading');
const empty = computed(() => store.list.status === 'empty');
const errorMessage = computed(() => store.list.status === 'error'
  ? (store.list.error?.userMessage || '预约列表加载失败') : '');

const load = () => store.fetchList({
  pageNumber: pageNumber.value,
  pageSize: pageSize.value,
  status: status.value || undefined,
}).catch(() => {});

const applyFilter = () => {
  pageNumber.value = 1;
  store.filters.status = status.value;
  load();
};
const changePage = (page) => { pageNumber.value = page; load(); };
const changeSize = (size) => { pageSize.value = size; pageNumber.value = 1; load(); };
const openCreate = () => {
  handoffError.value = '';
  if (!isResourceId(resourceId.value) || !isValidDate(bookingDate.value)) {
    handoffError.value = '请输入有效的资源编号和预约日期';
    return;
  }
  createOpen.value = true;
};

const consumeSafeQuery = (query) => {
  const handoff = parseBookingHandoff(query);
  if (!handoff) return;
  resourceId.value = handoff.resourceId;
  bookingDate.value = handoff.date;
  createOpen.value = true;
};

watch(() => route.query, consumeSafeQuery, { immediate: true });
onMounted(load);
</script>

<template>
  <main aria-labelledby="booking-list-title">
    <h1 id="booking-list-title">我的预约</h1>

    <section aria-labelledby="create-booking-title">
      <h2 id="create-booking-title">创建预约</h2>
      <label>资源编号 <input v-model.trim="resourceId" inputmode="numeric" /></label>
      <label>预约日期 <input v-model="bookingDate" type="date" /></label>
      <el-button type="primary" @click="openCreate">选择可用时段</el-button>
      <p v-if="handoffError" role="alert">{{ handoffError }}</p>
    </section>

    <form aria-label="预约状态筛选" @submit.prevent="applyFilter">
      <label>
        状态
        <select v-model="status">
          <option value="">全部状态</option>
          <option v-for="item in BOOKING_STATUS" :key="item" :value="item">{{ statusLabels[item] }}</option>
        </select>
      </label>
      <button type="submit">筛选</button>
    </form>

    <p v-if="errorMessage" role="alert">
      {{ errorMessage }} <button type="button" @click="load">重试</button>
    </p>
    <p v-else-if="loading" aria-live="polite">加载中…</p>
    <p v-else-if="empty">暂无预约</p>
    <template v-else>
      <el-table :data="records" row-key="id" aria-label="我的预约列表">
        <el-table-column prop="bookingNo" label="预约号" />
        <el-table-column prop="resourceId" label="资源编号" />
        <el-table-column prop="startTime" label="开始时间" />
        <el-table-column prop="endTime" label="结束时间" />
        <el-table-column label="状态">
          <template #default="{ row }"><el-tag>{{ statusLabels[row.status] }}</el-tag></template>
        </el-table-column>
        <el-table-column label="操作" width="220">
          <template #default="{ row }">
            <RouterLink :to="`/bookings/${row.id}`">查看详情</RouterLink>
            <CancelBookingButton :booking="row" />
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-model:current-page="pageNumber"
        v-model:page-size="pageSize"
        :page-sizes="[10, 20, 50, 100]"
        :total="total"
        layout="total, sizes, prev, pager, next"
        @current-change="changePage"
        @size-change="changeSize"
      />
    </template>

    <CreateBookingPanel
      v-model="createOpen"
      :resource-id="resourceId"
      :date="bookingDate"
    />
  </main>
</template>

<style scoped>
section, form { display: flex; align-items: end; flex-wrap: wrap; gap: 0.75rem; margin-bottom: 1.5rem; }
label { display: grid; gap: 0.25rem; }
</style>
