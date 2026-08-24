<template>
  <main class="category-page">
    <h1>分类管理</h1>
    <p v-if="forbidden" class="error" role="alert">仅 ADMIN 可管理分类。</p>
    <template v-else>
      <p v-if="catalog.categories.status === 'loading'" role="status">正在加载分类…</p>
      <section v-if="catalog.categories.status === 'error'" class="error-panel" role="alert">
        <p>{{ catalog.categories.error?.userMessage || '分类加载失败，请重试。' }}</p>
        <button type="button" @click="reload" :disabled="catalog.categories.pending">重试</button>
      </section>
      <p v-if="catalog.categories.status === 'empty' || (catalog.categories.status === 'success' && !flatCategories.length)" class="empty">暂无分类，请创建第一个分类。</p>
      <section class="editor" aria-labelledby="editor-title">
        <h2 id="editor-title">{{ editingId ? '编辑分类' : '新建分类' }}</h2>
        <form @submit.prevent="submitForm" novalidate>
          <label for="category-name">名称</label>
          <input id="category-name" v-model="form.name" :aria-invalid="Boolean(fieldErrors.name)" required maxlength="50" />
          <p v-if="fieldErrors.name" class="field-error">{{ fieldErrors.name }}</p>
          <label for="category-parent">父分类</label>
          <select id="category-parent" v-model="form.parentId">
            <option value="0">无（顶级分类）</option>
            <option v-for="item in parentOptions" :key="item.id" :value="item.id">{{ item.label }}</option>
          </select>
          <p v-if="fieldErrors.parentId" class="field-error">{{ fieldErrors.parentId }}</p>
          <label for="category-sort">排序</label>
          <input id="category-sort" v-model="form.sortOrder" type="number" step="1" />
          <p v-if="fieldErrors.sortOrder" class="field-error">{{ fieldErrors.sortOrder }}</p>
          <label for="category-icon">图标（可选）</label>
          <input id="category-icon" v-model="form.icon" maxlength="255" />
          <p v-if="fieldErrors.icon" class="field-error">{{ fieldErrors.icon }}</p>
          <div class="actions">
            <button type="submit" :disabled="pending">{{ pending ? '保存中…' : (editingId ? '保存修改' : '创建分类') }}</button>
            <button v-if="editingId" type="button" @click="resetForm" :disabled="pending">取消编辑</button>
          </div>
        </form>
      </section>
      <p v-if="message" :class="messageType" role="status">{{ message }}</p>
      <ul class="category-tree" aria-label="分类树">
        <li v-for="item in flatCategories" :key="item.id" :style="{ paddingLeft: `${item.depth * 1.5}rem` }">
          <span class="category-label">{{ item.name }}</span>
          <small>ID {{ item.id }} · 排序 {{ item.sortOrder }}</small>
          <button type="button" @click="startEdit(item.raw)" :disabled="pending">编辑</button>
          <button type="button" @click="removeCategory(item.raw)" :disabled="pending">删除</button>
        </li>
      </ul>
    </template>
  </main>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue';
import { useAuthStore } from '../../../stores/auth';
import { useResourceCatalogStore } from '../../../stores/resourceCatalog';
import { normalizeCategoryPayload, validateCategory } from '../../../components/resource/validation';

const auth = useAuthStore();
const catalog = useResourceCatalogStore();
const forbidden = computed(() => auth.role !== 'ADMIN');
const pending = ref(false);
const editingId = ref('');
const message = ref('');
const messageType = ref('');
const fieldErrors = reactive({ name: '', parentId: '', sortOrder: '', icon: '' });
const form = reactive({ name: '', parentId: '0', sortOrder: 0, icon: '' });

const sourceCategories = computed(() => Array.isArray(catalog.categories.data) ? catalog.categories.data : []);
const flatCategories = computed(() => {
  const output = [];
  const visit = (items, depth = 0) => {
    [...(items || [])]
      .sort((a, b) => Number(a.sortOrder ?? 0) - Number(b.sortOrder ?? 0))
      .forEach((item) => {
        const id = String(item.id);
        output.push({
          id,
          name: item.name,
          sortOrder: item.sortOrder ?? 0,
          depth,
          raw: item,
          label: `${'　'.repeat(depth)}${item.name}`,
        });
        visit(item.children, depth + 1);
      });
  };
  visit(sourceCategories.value);
  return output;
});
const parentOptions = computed(() => flatCategories.value.filter((item) => item.id !== editingId.value));

function clearErrors() { Object.keys(fieldErrors).forEach((key) => { fieldErrors[key] = ''; }); }
function resetForm() { editingId.value = ''; Object.assign(form, { name: '', parentId: '0', sortOrder: 0, icon: '' }); clearErrors(); }
function startEdit(item) { editingId.value = String(item.id); Object.assign(form, normalizeCategoryPayload(item)); clearErrors(); message.value = ''; }
function applyErrors(errors) { clearErrors(); const keys = ['name', 'parentId', 'sortOrder', 'icon']; errors.forEach((error, index) => { fieldErrors[keys[index] || 'name'] ||= error; }); }
async function reload() { if (!forbidden.value) { try { await catalog.fetchCategories({ force: true }); } catch { /* store state renders the error */ } } }
async function submitForm() {
  if (forbidden.value || pending.value) return;
  const result = validateCategory(form);
  if (!result.valid) { applyErrors(result.errors); message.value = '请修正表单后再提交。'; messageType.value = 'error'; return; }
  pending.value = true;
  message.value = '';
  try {
    if (editingId.value) await catalog.updateCategory(editingId.value, result.value);
    else await catalog.createCategory(result.value);
    message.value = editingId.value ? '分类更新成功。' : '分类创建成功。';
    messageType.value = 'success';
    resetForm();
  } catch (error) {
    message.value = error?.userMessage || '操作失败，请稍后重试。';
    messageType.value = 'error';
  } finally {
    pending.value = false;
  }
}
async function removeCategory(item) {
  if (forbidden.value || pending.value) return;
  const id = String(item.id);
  if (!window.confirm(`确认删除分类“${item.name}”？其子分类可能无法删除。`)) return;
  pending.value = true;
  message.value = '';
  try {
    await catalog.deleteCategory(id);
    message.value = '分类删除成功。';
    messageType.value = 'success';
    if (editingId.value === id) resetForm();
  } catch (error) {
    message.value = error?.userMessage || '删除失败，可能存在关联资源或子分类。';
    messageType.value = 'error';
  } finally {
    pending.value = false;
  }
}

onMounted(async () => { await auth.ensureHydrated(); if (!forbidden.value) await reload(); });
</script>

<style scoped>
.category-page { max-width: 960px; margin: 2rem auto; padding: 1.5rem; }
.editor { border: 1px solid #d8dee8; border-radius: 10px; padding: 1rem; margin: 1rem 0; }
form { display: grid; gap: .45rem; max-width: 32rem; }
input, select, button { min-height: 2.2rem; padding: .35rem .6rem; font: inherit; }
button { cursor: pointer; margin-right: .4rem; }
button:disabled { opacity: .55; cursor: not-allowed; }
.actions { margin-top: .5rem; }
.category-tree { list-style: none; padding: 0; border-top: 1px solid #e5e7eb; }
.category-tree li { display: flex; gap: .6rem; align-items: center; padding: .65rem .25rem; border-bottom: 1px solid #e5e7eb; }
.category-label { font-weight: 600; min-width: 10rem; }
.category-tree small { color: #667085; margin-right: auto; }
.error, .field-error, .error-panel { color: #b42318; }
.success { color: #087443; }
.empty { color: #667085; padding: 1rem 0; }
@media (max-width: 640px) { .category-tree li { flex-wrap: wrap; } .category-label { min-width: 8rem; } }
</style>
