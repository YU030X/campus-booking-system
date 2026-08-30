<template>
  <main class="approvals-page">
    <h1>预约审批</h1>
    <p v-if="!isAdmin" class="error" role="alert">仅 ADMIN 可访问预约审批。</p>
    <template v-else>
      <p v-if="pageState.phase === 'loading'" role="status">正在加载待审批预约…</p>
      <p v-if="pageState.phase === 'error'" class="error" role="alert">
        {{ pageState.error?.adminMessage || pageState.error?.message || '加载失败' }}
        <button type="button" @click="retry">重试</button>
      </p>
      <p v-if="pageState.phase === 'empty'" class="empty">暂无待审批预约。</p>

      <table v-if="pageState.records.length" class="approval-table">
        <caption>待审批列表（服务端 createdAt ASC,id ASC 顺序）</caption>
        <thead>
          <tr><th>ID</th><th>预约号</th><th>用户ID</th><th>资源ID</th><th>开始时间</th><th>结束时间</th><th>事由</th><th>人数</th><th>状态</th><th>操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="row in pageState.records" :key="row.id">
            <td>{{ row.id }}</td>
            <td>{{ row.bookingNo }}</td>
            <td>{{ row.userId }}</td>
            <td>{{ row.resourceId }}</td>
            <td>{{ row.startTime }}</td>
            <td>{{ row.endTime }}</td>
            <td>{{ row.purpose }}</td>
            <td>{{ row.attendeeCount }}</td>
            <td>{{ row.status }}</td>
            <td>
              <template v-if="canActOn(row)">
                <button type="button" :disabled="isBusy(row.id)" @click="askAction(row, 'approve')">批准</button>
                <button type="button" :disabled="isBusy(row.id)" @click="askAction(row, 'reject')">驳回</button>
              </template>
              <span v-else>只读（{{ row.status }}）</span>
              <button type="button" @click="viewDetail(row)">详情</button>
            </td>
          </tr>
        </tbody>
      </table>

      <section v-if="selected.phase === 'ready'" class="detail" aria-labelledby="approval-detail-title">
        <h2 id="approval-detail-title">选中预约（来自列表项或动作返回）</h2>
        <dl>
          <dt>ID</dt><dd>{{ selected.booking.id }}</dd>
          <dt>预约号</dt><dd>{{ selected.booking.bookingNo }}</dd>
          <dt>用户ID</dt><dd>{{ selected.booking.userId }}</dd>
          <dt>资源ID</dt><dd>{{ selected.booking.resourceId }}</dd>
          <dt>起止</dt><dd>{{ selected.booking.startTime }} ~ {{ selected.booking.endTime }}</dd>
          <dt>事由</dt><dd>{{ selected.booking.purpose }}</dd>
          <dt>人数</dt><dd>{{ selected.booking.attendeeCount }}</dd>
          <dt>状态</dt><dd>{{ selected.booking.status }}</dd>
          <dt>签到时间</dt><dd>{{ selected.booking.checkinTime ?? '—' }}</dd>
          <dt>取消时间</dt><dd>{{ selected.booking.cancelTime ?? '—' }}</dd>
          <dt>取消原因</dt><dd>{{ selected.booking.cancelReason ?? '—' }}</dd>
          <dt>创建/更新</dt><dd>{{ selected.booking.createdAt }} / {{ selected.booking.updatedAt }}</dd>
        </dl>
        <button type="button" @click="closeDetail">关闭详情</button>
      </section>

      <ApprovalCommentDialog
        :target="dialog.target"
        :action="dialog.action"
        :current-op-state="dialogOpState"
        @confirmed="confirmAction"
        @cancelled="closeDialog"
      />

      <nav class="pager" aria-label="分页">
        <button type="button" :disabled="pageNumber <= 1 || listLoading" @click="goPage(pageNumber - 1)">上一页</button>
        <span>第 {{ pageNumber }} 页 · 每页 {{ pageSize }} 条 · 共 {{ pageState.total }} 条</span>
        <button type="button" :disabled="pageNumber * pageSize >= pageState.total || listLoading" @click="goPage(pageNumber + 1)">下一页</button>
      </nav>
    </template>
  </main>
</template>

<script setup>
import { computed, onMounted, reactive } from 'vue';
import { useAuthStore } from '../../../stores/auth';
import { useAdminApprovalsStore } from '../../../stores/adminApprovals';
import ApprovalCommentDialog from '../../../components/admin/approvals/ApprovalCommentDialog.vue';

const auth = useAuthStore();
const store = useAdminApprovalsStore();

const isAdmin = computed(() => auth.isAuthenticated && auth.role === 'ADMIN');
const pageState = computed(() => store.page);
const pageNumber = computed(() => store.pageNumber);
const pageSize = computed(() => store.pageSize);
const listLoading = computed(() => pageState.value.phase === 'loading');
const selected = computed(() => store.selected);

const dialog = reactive({ target: null, action: '' });

const dialogOpState = computed(() => {
  if (!dialog.target) return null;
  const entry = store.actions[String(dialog.target.id)];
  return entry && entry.key === `approval:${dialog.target.id}:${dialog.action}` ? entry : null;
});

const dialogBusy = computed(() => Object.values(store.actions).some((entry) => entry.phase === 'loading'));

onMounted(() => {
  if (!isAdmin.value) return;
  store.fetchList().catch(() => {});
});

function canActOn(row) {
  return row.status === 'PENDING_APPROVAL';
}

function isBusy(id) {
  return dialogBusy.value || store.actions[String(id)]?.phase === 'loading';
}

function askAction(row, action) {
  if (!canActOn(row) || isBusy(row.id)) return;
  dialog.target = row;
  dialog.action = action;
}

function confirmAction({ id, action, comment }) {
  store.requestAction(id, action, comment)
    .then(() => {
      closeDialog();
    })
    .catch(() => {
      /* dialog stays open with untouched comment input; live op error is rendered */
    });
}

function viewDetail(row) {
  store.selectFrom(row).catch(() => {});
}

function closeDetail() {
  store.clearSelection().catch(() => {});
}

function goPage(target) {
  store.setPage(target).catch(() => {});
}

function retry() {
  store.retry().catch(() => {});
}

function closeDialog() {
  dialog.target = null;
  dialog.action = '';
}
</script>

<style scoped>
.approvals-page { padding: 16px; }
.error { color: #c0392b; }
.empty { color: #7f8c8d; }
.approval-table { width: 100%; border-collapse: collapse; }
.approval-table th, .approval-table td { border-bottom: 1px solid #ebeef5; padding: 6px 10px; text-align: left; font-size: 14px; }
.detail { margin-top: 16px; border-top: 1px solid #ebeef5; padding-top: 8px; }
.detail dl { display: grid; grid-template-columns: max-content 1fr; gap: 4px 12px; font-size: 14px; }
.pager { display: flex; gap: 16px; align-items: center; margin-top: 12px; }
</style>
