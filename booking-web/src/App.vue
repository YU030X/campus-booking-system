<script setup>
import { RouterView } from 'vue-router';
import AuthenticatedLayout from './layouts/AuthenticatedLayout.vue';
</script>

<template>
  <RouterView v-slot="{ Component, route }">
    <AuthenticatedLayout v-if="!route.meta.public">
      <component :is="Component" />
    </AuthenticatedLayout>
    <component v-else :is="Component" />
  </RouterView>
</template>
