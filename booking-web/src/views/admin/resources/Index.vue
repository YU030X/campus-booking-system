<template>
  <main class="resource-page">
    <h1>资源管理</h1>
    <p v-if="forbidden" class="error" role="alert">仅 ADMIN 可管理资源。</p>
    <template v-else>
      <section class="filters" aria-label="筛选资源">
        <label>分类<select v-model="filters.categoryId" @change="reloadFirst"><option value="">全部</option><option v-for="c in categories" :key="c.id" :value="c.id">{{ c.label }}</option></select></label>
        <label>状态<select v-model="filters.status" @change="reloadFirst"><option value="">全部</option><option value="0">停用</option><option value="1">可用</option><option value="2">维护中</option></select></label>
        <label>关键词<input v-model="filters.keyword" @keyup.enter="reloadFirst" placeholder="名称或位置" /></label>
        <button type="button" @click="reloadFirst">搜索</button>
      </section>
      <p v-if="categoriesError" class="error" role="alert">{{ categoriesError }} <button type="button" @click="retryCategories">重试分类</button></p>
      <p v-if="listError" class="error" role="alert">{{ listError }} <button type="button" @click="reload">重试</button></p>
      <p v-if="loading" role="status">正在加载资源…</p>
      <p v-if="!loading && !listError && !records.length" class="empty">暂无资源。</p>
      <table v-if="records.length" class="resource-table">
        <caption>资源列表</caption>
        <thead><tr><th>ID</th><th>名称</th><th>分类</th><th>位置</th><th>容量</th><th>状态</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="item in records" :key="item.id">
            <td>{{ item.id }}</td><td>{{ item.name }}</td><td>{{ categoryName(item.categoryId) }}</td><td>{{ item.location || '—' }}</td><td>{{ item.capacity ?? '—' }}</td>
            <td><select :value="String(item.status ?? 0)" :disabled="statusPending[String(item.id)]" @change="changeStatus(item, $event.target.value)" :aria-label="`修改${item.name}状态`"><option value="0">停用</option><option value="1">可用</option><option value="2">维护中</option></select></td>
            <td><button type="button" @click="startEdit(item)" :disabled="pending">编辑</button></td>
          </tr>
        </tbody>
      </table>
      <nav class="pager" aria-label="分页"><button type="button" :disabled="pageNumber <= 1 || loading" @click="goPage(pageNumber - 1)">上一页</button><span>第 {{ pageNumber }} 页，共 {{ total }} 条</span><button type="button" :disabled="pageNumber * pageSize >= total || loading" @click="goPage(pageNumber + 1)">下一页</button></nav>
      <section class="editor" aria-labelledby="editor-title">
        <h2 id="editor-title">{{ editingId ? '编辑资源' : '新建资源' }}</h2>
        <form @submit.prevent="submitForm" novalidate>
          <label>分类ID<input v-model="form.categoryId" inputmode="numeric" :aria-invalid="!!fieldErrors.categoryId" required /></label><p v-if="fieldErrors.categoryId" class="field-error">{{ fieldErrors.categoryId }}</p>
          <label>名称<input v-model="form.name" maxlength="100" :aria-invalid="!!fieldErrors.name" required /></label><p v-if="fieldErrors.name" class="field-error">{{ fieldErrors.name }}</p>
          <label>位置<input v-model="form.location" maxlength="200" /></label><p v-if="fieldErrors.location" class="field-error">{{ fieldErrors.location }}</p>
          <label>容量<input v-model="form.capacity" type="number" min="1" /></label><p v-if="fieldErrors.capacity" class="field-error">{{ fieldErrors.capacity }}</p>
          <label>描述<textarea v-model="form.description" rows="3"></textarea></label><p v-if="fieldErrors.description" class="field-error">{{ fieldErrors.description }}</p>
          <label>图片地址<input v-model="form.images" maxlength="1000" /></label>
          <label><input v-model="form.needApproval" type="checkbox" /> 需要审批</label>
          <label>提前天数<input v-model="form.maxAdvanceDays" type="number" min="0" max="365" /></label><p v-if="fieldErrors.maxAdvanceDays" class="field-error">{{ fieldErrors.maxAdvanceDays }}</p>
          <label>最短时长（分钟）<input v-model="form.minDurationMinutes" type="number" step="30" /></label><p v-if="fieldErrors.minDurationMinutes" class="field-error">{{ fieldErrors.minDurationMinutes }}</p>
          <label>最长时长（分钟）<input v-model="form.maxDurationMinutes" type="number" step="30" /></label><p v-if="fieldErrors.maxDurationMinutes" class="field-error">{{ fieldErrors.maxDurationMinutes }}</p>
          <label>状态<select v-model="form.status"><option value="0">停用</option><option value="1">可用</option><option value="2">维护中</option></select></label>
          <p v-if="globalError" class="error" role="alert">{{ globalError }}</p><p v-if="message" class="success" role="status">{{ message }}</p>
          <div><button type="submit" :disabled="pending">{{ pending ? '保存中…' : (editingId ? '保存修改' : '创建资源') }}</button><button v-if="editingId" type="button" @click="resetForm" :disabled="pending">取消</button></div>
        </form>
      </section>
    </template>
  </main>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue';
import { useAuthStore } from '../../../stores/auth';
import { useResourceCatalogStore } from '../../../stores/resourceCatalog';
import { normalizeResourcePayload, validateResource } from '../../../components/resource/validation';

const auth = useAuthStore();
const store = useResourceCatalogStore();
const forbidden = computed(() => auth.role !== 'ADMIN');
const filters = reactive({ categoryId: '', status: '', keyword: '' });
const pageNumber = ref(1);
const pageSize = ref(10);
const total = ref(0);
const loading = ref(false);
const pending = ref(false);
const editingId = ref('');
const message = ref('');
const globalError = ref('');
const listError = ref('');
const categoriesError = ref('');
const statusPending = reactive({});
const defaults = { categoryId: '', name: '', location: '', capacity: '', description: '', images: '', needApproval: false, maxAdvanceDays: 7, minDurationMinutes: 30, maxDurationMinutes: 120, status: '1' };
const form = reactive({ ...defaults });
const fieldErrors = reactive({ categoryId: '', name: '', location: '', capacity: '', description: '', maxAdvanceDays: '', minDurationMinutes: '', maxDurationMinutes: '' });
const categories = computed(() => {
  const output = [];
  const visit = (items, prefix = '') => (items || []).forEach((item) => {
    output.push({ id: String(item.id), name: item.name, label: `${prefix}${item.name}` });
    visit(item.children, `${prefix}└ `);
  });
  visit(store.categories.data);
  return output;
});
const records = computed(() => store.pageResult?.records || []);

function categoryName(id) { return categories.value.find((category) => category.id === String(id))?.name || id || '—'; }
function clearErrors() { Object.keys(fieldErrors).forEach((key) => { fieldErrors[key] = ''; }); globalError.value = ''; }
function resetForm() { editingId.value = ''; Object.assign(form, defaults); clearErrors(); }
function startEdit(item) { editingId.value = String(item.id); Object.assign(form, normalizeResourcePayload(item)); form.status = String(item.status ?? 1); clearErrors(); message.value = ''; }
function applyErrors(errors) { clearErrors(); const keys = Object.keys(fieldErrors); errors.forEach((error, index) => { fieldErrors[keys[index] || 'name'] ||= error; }); }
function errorMessage(error) { const status = error?.response?.status; return ({ 400: '请求参数无效。', 403: '无权执行此操作。', 404: '资源不存在。', 409: '资源状态冲突，请刷新后重试。' })[status] || error?.userMessage || '操作失败，请稍后重试。'; }
async function reload() {
  if (forbidden.value) return;
  loading.value = true;
  listError.value = '';
  try {
    const data = await store.fetchList({ ...filters, pageNumber: pageNumber.value, pageSize: pageSize.value });
    total.value = data?.total ?? store.pageResult.total ?? 0;
  } catch (error) {
    listError.value = errorMessage(error);
  } finally {
    loading.value = false;
  }
}
async function retryCategories() { categoriesError.value = ''; try { await store.fetchCategories({ force: true }); } catch (error) { categoriesError.value = errorMessage(error); } }
async function reloadFirst() { pageNumber.value = 1; await reload(); }
async function goPage(page) { pageNumber.value = page; await reload(); }
async function submitForm() {
  if (forbidden.value || pending.value) return;
  const result = validateResource(form);
  if (!result.valid) { applyErrors(result.errors); globalError.value = '请修正表单后再提交。'; return; }
  pending.value = true;
  message.value = '';
  globalError.value = '';
  try {
    if (editingId.value) await store.update(editingId.value, result.value);
    else await store.create(result.value);
    message.value = editingId.value ? '资源更新成功。' : '资源创建成功。';
    resetForm();
    await reload();
  } catch (error) {
    globalError.value = errorMessage(error);
  } finally {
    pending.value = false;
  }
}
async function changeStatus(item, status) {
  const id = String(item.id);
  if (forbidden.value || statusPending[id]) return;
  statusPending[id] = true;
  try {
    await store.updateStatus(id, Number(status));
    message.value = '状态更新成功。';
    await reload();
  } catch (error) {
    globalError.value = errorMessage(error);
  } finally {
    delete statusPending[id];
  }
}

onMounted(async () => { await auth.ensureHydrated(); if (!forbidden.value) await Promise.all([retryCategories(), reload()]); });
</script>

<style scoped>
.resource-page { max-width: 1100px; margin: 2rem auto; padding: 1.5rem; }
.filters { display: flex; gap: .75rem; flex-wrap: wrap; align-items: end; margin: 1rem 0; }
.filters label, .editor label { display: grid; gap: .25rem; }
input, select, textarea, button { font: inherit; padding: .4rem; min-height: 2.2rem; }
button { cursor: pointer; }
button:disabled { opacity: .55; cursor: not-allowed; }
.resource-table { width: 100%; border-collapse: collapse; margin-top: 1rem; }
.resource-table th, .resource-table td { border-bottom: 1px solid #ddd; padding: .55rem; text-align: left; }
.editor { margin-top: 2rem; border: 1px solid #ddd; border-radius: 8px; padding: 1rem; }
.editor form { display: grid; gap: .5rem; max-width: 38rem; }
.pager { display: flex; justify-content: center; gap: 1rem; padding: 1rem; }
.error, .field-error { color: #b42318; }
.success { color: #087443; }
.empty { padding: 2rem; color: #667085; }
@media (max-width: 700px) { .resource-table { font-size: .85rem; } .resource-table th:nth-child(4), .resource-table td:nth-child(4) { display: none; } }
</style>
