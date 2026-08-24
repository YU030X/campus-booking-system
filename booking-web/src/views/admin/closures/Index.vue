<template>
  <main class="admin-closures">
    <h1>闭馆日期</h1>
    <p>后端没有 GET；以下仅显示本会话 POST 返回的权威记录。</p>
    <p v-if="forbidden" class="error" role="alert">仅 ADMIN 可管理闭馆日期。</p>
    <fieldset :disabled="forbidden || pending">
      <label>范围 ID（0 表示全局） <input v-model="scopeId" inputmode="numeric" /></label>
      <label>闭馆日期 <input v-model="closureDate" type="date" /></label>
      <label>原因 <input v-model="reason" maxlength="200" /></label>
      <button type="button" @click="add">新增闭馆日</button>
    </fieldset>
    <p v-if="message" class="status" :class="status" role="status">{{ message }}</p>
    <p v-else-if="!forbidden && records.length === 0" role="status" class="empty-session">当前会话尚未新增闭馆记录；后端不提供 GET 历史列表。</p>
    <section v-for="(record, index) in records" :key="String(record.id ?? record.closureId ?? index)" class="record">
      <span>{{ record.closureDate }} · {{ record.reason || '未填写原因' }}</span>
      <button type="button" :disabled="pending" @click="remove(record)">删除</button>
    </section>
  </main>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue';
import { useAuthStore } from '../../../stores/auth';
import { useResourceCatalogStore } from '../../../stores/resourceCatalog';

const auth = useAuthStore();
const store = useResourceCatalogStore();
const scopeId = ref('0');
const closureDate = ref('');
const reason = ref('');
const pending = ref(false);
const message = ref('');
const status = ref('');
const forbidden = computed(() => auth.role !== 'ADMIN');
const records = computed(() => store.closureRecords[String(scopeId.value)] || []);

function validate() {
  if (!/^\d+$/.test(scopeId.value)) return '范围 ID 必须是 0 或正数字字符串';
  if (!/^\d{4}-\d{2}-\d{2}$/.test(closureDate.value)) return '请选择有效闭馆日期';
  const [year, month, day] = closureDate.value.split('-').map(Number);
  const parsed = new Date(Date.UTC(year, month - 1, day));
  if (parsed.getUTCFullYear() !== year || parsed.getUTCMonth() !== month - 1 || parsed.getUTCDate() !== day) return '请选择有效闭馆日期';
  if ([...String(reason.value).trim()].length > 200) return '闭馆原因不能超过200字符';
  return '';
}
function normalize() { const value = String(reason.value).trim(); return { closureDate: closureDate.value, reason: value || null }; }
async function add() {
  if (forbidden.value || pending.value) return;
  const error = validate();
  if (error) { message.value = error; status.value = 'error'; return; }
  pending.value = true;
  message.value = '';
  try {
    await store.addClosure(scopeId.value, normalize());
    closureDate.value = '';
    reason.value = '';
    message.value = '闭馆日期已新增（显示后端权威响应）。';
    status.value = 'success';
  } catch (requestError) {
    message.value = requestError.userMessage || '新增失败，请重试。';
    status.value = 'error';
  } finally {
    pending.value = false;
  }
}
async function remove(record) {
  if (forbidden.value || pending.value) return;
  const id = String(record.id ?? record.closureId ?? '');
  if (!/^\d+$/.test(id)) { message.value = '记录缺少有效字符串 ID'; status.value = 'error'; return; }
  pending.value = true;
  try {
    await store.deleteClosure(scopeId.value, id);
    message.value = '闭馆日期已删除。';
    status.value = 'success';
  } catch (error) {
    const httpStatus = error.response?.status;
    message.value = httpStatus === 409
      ? '删除冲突，请稍后重试。'
      : httpStatus === 404
        ? '记录不存在，可能已被删除。'
        : error.userMessage || '删除失败，请重试。';
    status.value = 'error';
  } finally {
    pending.value = false;
  }
}

onMounted(() => auth.ensureHydrated());
</script>

<style scoped>
.admin-closures { max-width: 760px; margin: 2rem auto; padding: 1rem; background: #fff; border-radius: 12px; }
fieldset { display: grid; gap: .75rem; }
label { display: grid; gap: .25rem; }
.record { display: flex; justify-content: space-between; margin: .75rem 0; padding: .6rem; border-bottom: 1px solid #ddd; }
.error, .status.error { color: #b42318; }
.status.success { color: #087443; }
h1 { margin: 0 0 .5rem; }
p { line-height: 1.6; }
input { min-height: 2rem; padding: .25rem .5rem; }
button { min-height: 2rem; padding: .25rem .75rem; cursor: pointer; }
button:disabled { cursor: not-allowed; opacity: .6; }
.record button { margin-left: 1rem; }
@media (max-width: 640px) { .record { align-items: flex-start; flex-direction: column; gap: .5rem; } }
</style>
