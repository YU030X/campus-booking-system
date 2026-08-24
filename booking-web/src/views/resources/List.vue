<template>
  <main class="resource-list" aria-labelledby="resource-title">
    <h1 id="resource-title">资源目录</h1>
    <form @submit.prevent="submit" aria-label="资源筛选">
      <label>关键词 <input v-model="keyword" type="search" placeholder="搜索资源名称" /></label>
      <label>分类 <select v-model="categoryId"><option value="">全部分类</option><option v-for="category in flatCategories" :key="category.id" :value="category.id">{{ category.label }}</option></select></label>
      <label>状态 <select v-model="status"><option value="">全部状态</option><option value="0">停用</option><option value="1">可用</option><option value="2">维护中</option></select></label>
      <button type="submit">筛选</button>
    </form>
    <p v-if="userMessage" role="alert">{{ userMessage }} <button type="button" @click="load">重试</button></p>
    <p v-else-if="loading" aria-live="polite">加载中…</p>
    <p v-else-if="isEmpty">暂无资源</p>
    <template v-else>
      <el-table
        :data="records"
        row-key="id"
        aria-label="资源列表"
      >
        <el-table-column prop="id" label="编号" />
        <el-table-column prop="name" label="名称"><template #default="{ row }"><RouterLink :to="`/resources/${row.id}`">{{ row.name }}</RouterLink></template></el-table-column>
        <el-table-column prop="categoryName" label="分类" />
        <el-table-column prop="location" label="位置" />
        <el-table-column prop="capacity" label="容量" />
        <el-table-column label="状态"><template #default="{ row }"><el-tag>{{ statusText(row.status) }}</el-tag></template></el-table-column>
        <el-table-column label="需审批"><template #default="{ row }">{{ row.needApproval ? '是' : '否' }}</template></el-table-column>
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
  </main>
</template>

<script setup>
// The catalog view owns read-only filtering and pagination state.
// Every request receives a fresh frozen snapshot of the visible controls.
import { computed, onMounted, ref } from 'vue';
import { useResourceCatalogStore } from '../../stores/resourceCatalog';

const store = useResourceCatalogStore();
const keyword = ref('');
const categoryId = ref('');
const status = ref('');
const pageNumber = ref(1);
const pageSize = ref(10);
const flatCategories = computed(() => {
  const result = [];
  const walk = (items, prefix = '') => (items || []).forEach((item) => {
    result.push({
      id: String(item.id),
      label: `${prefix}${item.name}`,
    });
    walk(item.children, `${prefix}└ `);
  });
  walk(store.categories.data);
  return result;
});
const records = computed(() => store.list.data?.records || []);
const total = computed(() => store.list.data?.total || 0);
const loading = computed(() => store.list.status === 'loading');
const isEmpty = computed(() => store.list.status === 'empty');
const userMessage = computed(() => store.list.status === 'error' ? (store.list.error?.userMessage || '资源加载失败') : '');
const params = () => Object.freeze({
  keyword: keyword.value.trim(),
  categoryId: categoryId.value,
  status: status.value,
  pageNumber: String(pageNumber.value),
  pageSize: String(pageSize.value),
});
const load = () => store.fetchList(params()).catch(() => {});
const submit = () => { pageNumber.value = 1; load(); };
const changePage = (page) => { pageNumber.value = page; load(); };
const changeSize = (size) => { pageSize.value = size; pageNumber.value = 1; load(); };
const statusText = (value) => ({ 0: '停用', 1: '可用', 2: '维护中' }[value] || '未知');

// Keep category traversal local so the API response remains untouched.
onMounted(() => { store.fetchCategories().catch(() => {}); load(); });
</script>
