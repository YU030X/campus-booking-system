import { h } from 'vue';
import { createRouter, createWebHistory } from 'vue-router';
import { useAuthStore, safeRedirect } from '../stores/auth';
import { installAuthHandlers } from '../api/http';
import Login from '../views/auth/Login.vue';
import Register from '../views/auth/Register.vue';
import ResourceList from '../views/resources/List.vue';
import ResourceDetail from '../views/resources/Detail.vue';
import MyBookingList from '../views/my-bookings/List.vue';
import MyBookingDetail from '../views/my-bookings/Detail.vue';
import Categories from '../views/admin/categories/Index.vue';
import AdminResources from '../views/admin/resources/Index.vue';
import Rules from '../views/admin/rules/Index.vue';
import Closures from '../views/admin/closures/Index.vue';
import AdminApprovals from '../views/admin/approvals/Index.vue';
import AdminUsers from '../views/admin/users/Index.vue';

const Placeholder = (title) => ({ render: () => h('section', [h('h2', title), h('p', 'Feature page placeholder')]) });
const studentRoles = ['STUDENT', 'ADMIN'];
const routes = [
  { path: '/login', component: Login, meta: { public: true } },
  { path: '/register', component: Register, meta: { public: true } },
  { path: '/resources', component: ResourceList, meta: { roles: studentRoles } },
  { path: '/resources/:id', component: ResourceDetail, meta: { roles: studentRoles } },
  { path: '/bookings', component: MyBookingList, meta: { roles: studentRoles } },
  { path: '/bookings/:id', component: MyBookingDetail, meta: { roles: studentRoles } },
  { path: '/admin/categories', component: Categories, meta: { roles: ['ADMIN'] } },
  { path: '/admin/resources', component: AdminResources, meta: { roles: ['ADMIN'] } },
  { path: '/admin/rules', component: Rules, meta: { roles: ['ADMIN'] } },
  { path: '/admin/closures', component: Closures, meta: { roles: ['ADMIN'] } },
  { path: '/admin/approvals', component: AdminApprovals, meta: { roles: ['ADMIN'] } },
  { path: '/admin/users', component: AdminUsers, meta: { roles: ['ADMIN'] } },
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
