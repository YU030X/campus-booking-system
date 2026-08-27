<template>
  <main class="admin-rules">
    <h1>开放规则</h1>
    <p>后端没有查询接口；本页只编辑并提交当前资源的完整规则数组。</p>
    <p v-if="forbidden" class="error" role="alert">仅 ADMIN 可管理开放规则。</p>
    <fieldset :disabled="forbidden || pending">
      <label>资源 ID <input v-model="resourceId" list="resource-options" inputmode="numeric" /></label>
      <datalist id="resource-options"><option v-for="item in resources" :key="item.id" :value="String(item.id)">{{ item.name }}</option></datalist>
      <button type="button" @click="loadRules">载入本会话规则</button>
      <p>明确点击提交前不会伪造已有规则。</p>
      <section v-for="(rule, index) in rules" :key="index" class="rule-row">
        <label>星期 <select v-model.number="rule.dayOfWeek"><option v-for="day in 7" :key="day" :value="day">{{ day }}</option></select></label>
        <label>开始 <input v-model="rule.startTime" placeholder="09:00:00" /></label>
        <label>结束 <input v-model="rule.endTime" placeholder="18:00:00" /></label>
        <button type="button" @click="removeRule(index)">删除</button>
      </section>
      <button type="button" @click="addRule">添加时段</button>
      <button type="button" @click="saveRules">保存完整规则</button>
    </fieldset>
    <p v-if="message" class="status" :class="status" role="status">{{ message }}</p>
    <p v-if="resourceError" class="error" role="alert">{{ resourceError }} <button type="button" @click="retryResources">重试资源列表</button></p>
    <pre v-if="store.rulesByResource[resourceId]">{{ JSON.stringify(store.rulesByResource[resourceId], null, 2) }}</pre>
  </main>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue';
import { useAuthStore } from '../../../stores/auth';
import { useResourceCatalogStore } from '../../../stores/resourceCatalog';
import { normalizeRules, validateRules } from '../../../components/resource/validation';

const auth = useAuthStore();
const store = useResourceCatalogStore();
const resourceId = ref('');
const rules = ref([]);
const resources = computed(() => store.pageResult.records || []);
const pending = ref(false);
const message = ref('');
const status = ref('');
const resourceError = ref('');
const forbidden = computed(() => auth.role !== 'ADMIN');

async function retryResources() {
  resourceError.value = '';
  try { await store.fetchList({ pageSize: 100 }, { force: true }); }
  catch (error) { resourceError.value = error.userMessage || '资源列表加载失败，请重试。'; }
}
function validateResourceId(value) { return value && /^\d+$/.test(value) && value !== '0'; }
function addRule() { rules.value.push({ dayOfWeek: 1, startTime: '09:00:00', endTime: '18:00:00' }); }
function removeRule(index) { rules.value.splice(index, 1); }
function loadRules() { message.value = '后端无 GET；请直接编辑并提交完整数组。'; status.value = 'info'; }
async function saveRules() {
  if (forbidden.value || pending.value) return;
  if (!validateResourceId(resourceId.value)) { message.value = '资源 ID 必须是正数字字符串'; status.value = 'error'; return; }
  const normalized = normalizeRules(rules.value);
  const validation = validateRules(normalized);
  if (!validation.valid) { message.value = validation.errors[0] || '规则无效'; status.value = 'error'; return; }
  pending.value = true;
  message.value = '';
  try {
    const result = await store.replaceRules(resourceId.value, normalized);
    rules.value = Array.isArray(result) ? result : normalized;
    message.value = '规则保存成功（以后端权威响应为准）。';
    status.value = 'success';
  } catch (error) {
    message.value = error.userMessage || '保存失败，请重试。';
    status.value = 'error';
  } finally {
    pending.value = false;
  }
}

onMounted(async () => { await auth.ensureHydrated(); if (!forbidden.value) await retryResources(); });
</script>

<style scoped>
.admin-rules { max-width: 900px; margin: 2rem auto; padding: 1rem; background: #fff; border-radius: 12px; }
.rule-row { display: flex; gap: .75rem; align-items: end; margin: .75rem 0; }
label { display: grid; gap: .25rem; }
button { margin: .4rem; min-height: 2rem; padding: .25rem .75rem; cursor: pointer; }
button:disabled { cursor: not-allowed; opacity: .6; }
.error, .status.error { color: #b42318; }
.status.success { color: #087443; }
h1 { margin: 0 0 .5rem; }
p { line-height: 1.6; }
input, select { min-height: 2rem; padding: .25rem .5rem; }
.rule-row label:first-child { min-width: 7rem; }
.rule-row input { width: 8rem; }
pre { overflow: auto; background: #f6f7f9; padding: 1rem; }
@media (max-width: 640px) { .rule-row { flex-wrap: wrap; } .rule-row input { width: 100%; } }
</style>
