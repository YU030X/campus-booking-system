<template>
  <main class="users-page">
    <h1>用户管理</h1>
    <p v-if="!isAdmin" class="error" role="alert">仅 ADMIN 可访问用户管理。</p>
    <template v-else>
      <section class="filters" aria-label="筛选用户">
        <label>关键词<input v-model="localFilters.keyword" @keyup.enter="submitFilters" placeholder="用户名 / 姓名 / 学号 / 手机号" /></label>
        <label>角色<select v-model="localFilters.role"><option value="">全部</option><option value="STUDENT">学生</option><option value="ADMIN">管理员</option></select></label>
        <label>状态<select v-model="localFilters.status"><option value="">全部</option><option value="0">停用</option><option value="1">启用</option></select></label>
        <button type="button" :disabled="listLoading" @click="submitFilters">搜索</button>
      </section>

      <p v-if="pageState.phase === 'loading'" role="status">正在加载用户…</p>
      <p v-if="pageState.phase === 'error'" class="error" role="alert">
        {{ pageState.error?.adminMessage || pageState.error?.message || '加载失败' }}
        <button type="button" @click="retry">重试</button>
      </p>
      <p v-if="pageState.phase === 'empty'" class="empty">暂无符合条件的用户。</p>

      <table v-if="pageState.records.length" class="user-table">
        <caption>用户列表（按服务端顺序）</caption>
        <thead>
          <tr><th>ID</th><th>用户名</th><th>姓名</th><th>学号</th><th>手机号</th><th>角色</th><th>信用分</th><th>状态</th><th>创建时间</th><th>操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="row in pageState.records" :key="row.id">
            <td>{{ row.id }}</td>
            <td>{{ row.username }}</td>
            <td>{{ row.realName || '—' }}</td>
            <td>{{ row.studentNo || '—' }}</td>
            <td>{{ row.phone || '—' }}</td>
            <td>{{ row.role }}</td>
            <td>{{ row.creditScore }}</td>
            <td>{{ row.status === 1 ? '启用' : '停用' }}</td>
            <td>{{ row.createdAt }}</td>
            <td>
              <button type="button" :disabled="isBusy(row.id)" @click="askStatus(row, row.status === 1 ? 0 : 1)">
                {{ row.status === 1 ? '禁用' : '启用' }}
              </button>
            </td>
          </tr>
        </tbody>
      </table>

      <UserStatusDialog
        :target="dialog.target"
        :target-status="dialog.status"
        :current-user-id="currentUserId"
        :op-state="dialogOpState"
        @confirmed="confirmStatus"
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
import { computed, onMounted, reactive, ref } from 'vue';
import { useAuthStore } from '../../../stores/auth';
import { useAdminUsersStore } from '../../../stores/adminUsers';
import UserStatusDialog from '../../../components/admin/users/UserStatusDialog.vue';

const auth = useAuthStore();
const store = useAdminUsersStore();

const isAdmin = computed(() => auth.isAuthenticated && auth.role === 'ADMIN');
const currentUserId = computed(() => String(auth.user?.id ?? ''));

const pageState = computed(() => store.page);
const pageNumber = computed(() => store.pageNumber);
const pageSize = computed(() => store.pageSize);
const listLoading = computed(() => pageState.value.phase === 'loading');

const localFilters = ref({ keyword: '', role: '', status: '' });
const dialog = reactive({ target: null, status: null });

const dialogOpState = computed(() => (
  dialog.target ? store.statusOps[String(dialog.target.id)] ?? null : null
));

onMounted(() => {
  if (!isAdmin.value) return;
  syncLocalFilters();
  store.fetchList().catch(() => {});
});

function syncLocalFilters() {
  const model = store.filters;
  localFilters.value = {
    keyword: model.keyword ?? '',
    role: model.role ?? '',
    status: model.status === 0 || model.status === 1 ? String(model.status) : '',
  };
}

async function submitFilters() {
  try {
    await store.applyFilters({
      keyword: localFilters.value.keyword,
      role: localFilters.value.role,
      status: localFilters.value.status,
    });
  } catch {
    /* phase/error surface through page state */
  }
}

function goPage(target) {
  store.setPage(target).catch(() => {});
}

function retry() {
  store.retry().catch(() => {});
}

function isBusy(id) {
  return store.statusOps[String(id)]?.phase === 'loading';
}

function askStatus(row, status) {
  if (isBusy(row.id)) return;
  dialog.target = row;
  dialog.status = status;
}

function confirmStatus({ id, status }) {
  store.changeStatus(id, status)
    .then(() => {
      closeDialog();
      syncLocalFilters();
    })
    .catch(() => {
      /* live opState carries the error into the dialog; truth already refreshed */
    });
}

function closeDialog() {
  dialog.target = null;
  dialog.status = null;
}
</script>

<style scoped>
.users-page { padding: 16px; }
.filters { display: flex; gap: 12px; align-items: center; margin-bottom: 12px; flex-wrap: wrap; }
.error { color: #c0392b; }
.empty { color: #7f8c8d; }
.user-table { width: 100%; border-collapse: collapse; }
.user-table th, .user-table td { border-bottom: 1px solid #ebeef5; padding: 6px 10px; text-align: left; font-size: 14px; }
.pager { display: flex; gap: 16px; align-items: center; margin-top: 12px; }
</style>
