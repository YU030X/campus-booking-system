<script setup>
import { computed } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '../stores/auth';

const store = useAuthStore();
const router = useRouter();
const isAdmin = computed(() => store.role === 'ADMIN');
const logout = async () => {
  store.logout();
  await router.replace('/login');
};
</script>

<template>
  <div class="shell">
    <header>
      <strong>Campus Booking</strong>
      <nav aria-label="Primary navigation">
        <RouterLink to="/resources">Resources</RouterLink>
        <RouterLink to="/bookings">Bookings</RouterLink>
        <template v-if="isAdmin">
          <RouterLink to="/admin/categories">Categories</RouterLink>
          <RouterLink to="/admin/resources">Admin resources</RouterLink>
          <RouterLink to="/admin/rules">Rules</RouterLink>
          <RouterLink to="/admin/closures">Closures</RouterLink>
          <RouterLink to="/admin/approvals">Approvals</RouterLink>
          <RouterLink to="/admin/users">Users</RouterLink>
        </template>
        <el-button type="primary" @click="logout">Logout</el-button>
      </nav>
    </header>
    <div v-if="store.forbidden" class="forbidden" role="alert" aria-live="polite">
      You do not have permission to view this page.
    </div>
    <main v-else>
      <slot />
    </main>
  </div>
</template>
