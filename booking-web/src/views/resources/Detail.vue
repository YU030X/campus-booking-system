<template>
  <main aria-labelledby="detail-title">
    <p v-if="notFound" role="alert">资源不存在</p>
    <p v-else-if="loading" aria-live="polite">加载中…</p>
    <p v-else-if="error" role="alert">
      {{ errorMessage }} <button type="button" @click="load">重试</button>
    </p>
    <article v-else-if="resource">
      <h1 id="detail-title">{{ resource.name }}</h1>
      <el-descriptions :column="1" border>
        <el-descriptions-item label="编号">{{ resource.id }}</el-descriptions-item>
        <el-descriptions-item label="分类">{{ resource.categoryName || resource.category?.name }}</el-descriptions-item>
        <el-descriptions-item label="描述">{{ resource.description }}</el-descriptions-item>
        <el-descriptions-item label="位置">{{ resource.location }}</el-descriptions-item>
        <el-descriptions-item label="容量">{{ resource.capacity }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag>{{ statusText(resource.status) }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="需审批">{{ resource.needApproval ? '是' : '否' }}</el-descriptions-item>
        <el-descriptions-item label="创建时间">{{ resource.createdAt }}</el-descriptions-item>
        <el-descriptions-item label="更新时间">{{ resource.updatedAt }}</el-descriptions-item>
      </el-descriptions>
    </article>
  </main>
</template>

<script setup>
import { computed, watch } from 'vue';
import { useRoute } from 'vue-router';
import { useResourceCatalogStore } from '../../stores/resourceCatalog';

const route = useRoute();
const store = useResourceCatalogStore();
const id = computed(() => String(route.params.id ?? ''));
const requestNotFound = computed(() => store.detail.status === 'error'
  && (store.detail.error?.code === 40400 || store.detail.error?.response?.status === 404));
const notFound = computed(() => id.value === '0' || !id.value || requestNotFound.value);
const resource = computed(() => store.detail.data);
const loading = computed(() => !notFound.value && store.detail.status === 'loading');
const error = computed(() => !notFound.value && store.detail.status === 'error');
const errorMessage = computed(() => store.detail.error?.userMessage || '资源加载失败');
const load = () => { if (!notFound.value) store.fetchDetail(id.value).catch(() => {}); };
const statusText = (value) => ({ 0: '停用', 1: '可用', 2: '维护中' }[value] || '未知');

watch(id, load, { immediate: true });
</script>
