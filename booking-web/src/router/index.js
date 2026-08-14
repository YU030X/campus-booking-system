import { h } from 'vue';
import { createRouter, createWebHistory } from 'vue-router';
import { useAuthStore, safeRedirect } from '../stores/auth';
import { installAuthHandlers } from '../api/http';
import Login from '../views/auth/Login.vue';
import Register from '../views/auth/Register.vue';

const Placeholder = (title) => ({ render: () => h('section', [h('h2', title), h('p', 'Feature page placeholder')]) });
const studentRoles = ['STUDENT', 'ADMIN'];
const routes = [
  { path: '/login', component: Login, meta: { public: true } },
  { path: '/register', component: Register, meta: { public: true } },
  { path: '/resources', component: Placeholder('Resources'), meta: { roles: studentRoles } },
  { path: '/resources/:id', component: Placeholder('Resource detail'), meta: { roles: studentRoles } },
  { path: '/bookings', component: Placeholder('Bookings'), meta: { roles: studentRoles } },
  { path: '/bookings/:id', component: Placeholder('Booking detail'), meta: { roles: studentRoles } },
  { path: '/admin/categories', component: Placeholder('Admin categories'), meta: { roles: ['ADMIN'] } },
  { path: '/admin/resources', component: Placeholder('Admin resources'), meta: { roles: ['ADMIN'] } },
  { path: '/admin/rules', component: Placeholder('Admin rules'), meta: { roles: ['ADMIN'] } },
  { path: '/admin/closures', component: Placeholder('Admin closures'), meta: { roles: ['ADMIN'] } },
  { path: '/admin/approvals', component: Placeholder('Admin approvals'), meta: { roles: ['ADMIN'] } },
  { path: '/admin/users', component: Placeholder('Admin users'), meta: { roles: ['ADMIN'] } },
];

const router = createRouter({ history: createWebHistory(), routes });
let handling401 = null;
installAuthHandlers({
  on401: () => {
    if (handling401) return handling401;
    const store = useAuthStore();
    const redirect = safeRedirect(router.currentRoute.value.fullPath);
    store.clear();
    handling401 = router.replace({ path: '/login', query: { redirect } }).finally(() => { handling401 = null; });
    return handling401;
  },
  on403: () => useAuthStore().setForbidden(true),
});

router.beforeEach(async (to) => {
  const store = useAuthStore();
  await store.ensureHydrated();
  if (to.matched.length === 0) return store.isAuthenticated ? (store.role === 'ADMIN' ? '/admin/resources' : '/resources') : '/login';
  if (to.meta.public) {
    if (store.isAuthenticated) return store.role === 'ADMIN' ? '/admin/resources' : '/resources';
    return true;
  }
  if (!store.isAuthenticated) return { path: '/login', query: { redirect: safeRedirect(to.fullPath) } };
  if (to.meta.roles && !to.meta.roles.includes(store.role)) {
    store.setForbidden(true);
    return false;
  }
  store.setForbidden(false);
  return true;
});

export default router;
