<template>
  <section class="notifications-page" aria-labelledby="notifications-title">
    <h1 id="notifications-title">我的通知</h1>
    <p v-if="error" class="error" role="alert">{{ error }} <button type="button" @click="load">重试</button></p>
    <p v-if="loading" role="status">正在加载通知…</p>
    <p v-if="!loading && !error && !records.length" class="empty">暂无通知。</p>
    <ol v-if="records.length" class="notifications" aria-live="polite">
      <li v-for="item in records" :key="item.id" :class="{ unread: !item.isRead }">
        <div class="heading">
          <strong>{{ item.title }}</strong>
          <span>{{ item.createdAt }}</span>
        </div>
        <p>{{ item.content }}</p>
        <div class="meta">
          <span>{{ item.type }}</span>
          <span>{{ item.isRead ? '已读' : '未读' }}</span>
          <button v-if="!item.isRead" type="button" :disabled="pending[item.id]"
            @click="markRead(item)">{{ pending[item.id] ? '处理中…' : '标记已读' }}</button>
        </div>
      </li>
    </ol>
    <nav class="pager" aria-label="通知分页">
      <button type="button" :disabled="pageNumber <= 1 || loading" @click="goPage(pageNumber - 1)">上一页</button>
      <span>第 {{ pageNumber }} 页，共 {{ total }} 条</span>
      <button type="button" :disabled="pageNumber * pageSize >= total || loading"
        @click="goPage(pageNumber + 1)">下一页</button>
    </nav>
  </section>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue';
import { supportApi } from '../../api/support.js';
import { useAuthStore } from '../../stores/auth';

const auth = useAuthStore();
const records = ref([]);
const pageNumber = ref(1);
const pageSize = ref(10);
const total = ref(0);
const loading = ref(false);
const error = ref('');
const pending = reactive({});

async function load() {
  loading.value = true;
  error.value = '';
  try {
    const page = await supportApi.notifications({ pageNumber: pageNumber.value, pageSize: pageSize.value });
    records.value = page.records;
    pageNumber.value = page.pageNumber;
    pageSize.value = page.pageSize;
    total.value = page.total;
  } catch (failure) {
    error.value = failure.supportMessage || '通知加载失败。';
  } finally {
    loading.value = false;
  }
}

async function markRead(item) {
  if (pending[item.id]) return;
  pending[item.id] = true;
  error.value = '';
  try {
    await supportApi.markNotificationRead(item.id);
    item.isRead = true;
  } catch (failure) {
    error.value = failure.supportMessage || '标记已读失败。';
  } finally {
    delete pending[item.id];
  }
}

async function goPage(value) {
  pageNumber.value = value;
  await load();
}

onMounted(async () => {
  await auth.ensureHydrated();
  if (auth.isAuthenticated) await load();
});
</script>

<style scoped>
.notifications-page { max-width: 56rem; margin: 2rem auto; padding: 1.5rem; }
.notifications { display: grid; gap: .75rem; padding: 0; list-style: none; }
.notifications li { border: 1px solid #d0d5dd; border-radius: .75rem; padding: 1rem; }
.notifications li.unread { border-left: .35rem solid #2563eb; background: #f8fbff; }
.heading, .meta, .pager { display: flex; gap: .75rem; align-items: center; justify-content: space-between; }
.meta { justify-content: flex-start; color: #475467; }
button { font: inherit; padding: .4rem .7rem; }
button:disabled { opacity: .55; }
.pager { justify-content: center; margin-top: 1rem; }
.error { color: #b42318; }
.empty { color: #667085; padding: 2rem 0; }
@media (max-width: 640px) { .heading { align-items: flex-start; flex-direction: column; } }
</style>
