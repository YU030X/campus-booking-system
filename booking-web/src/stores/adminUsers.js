import { defineStore } from 'pinia';
import { reactive } from 'vue';
import { createAdminUsersCore, createAdminUsersState, openAdminUsersApi } from '../api/adminUsers';

export const useAdminUsersStore = defineStore('adminUsers', {
  state: () => ({
    core: null,
  }),

  getters: {
    model: (state) => state.core?.state ?? null,
    page: (state) => state.core?.state.page ?? { phase: 'idle', records: [], total: 0 },
    filters: (state) => state.core?.state.filters ?? { keyword: '', role: '', status: '' },
    pageNumber: (state) => state.core?.state.pageNumber ?? 1,
    pageSize: (state) => state.core?.state.pageSize ?? 10,
    statusOps: (state) => state.core?.state.statusOps ?? {},
  },

  actions: {
    async ensure() {
      if (!this.core) {
        this.core = createAdminUsersCore(await openAdminUsersApi(), reactive(createAdminUsersState()));
      }
      return this.core;
    },
    fetchList(options = {}) {
      return this.ensure().then((core) => core.fetchList(options));
    },
    applyFilters(patch) {
      return this.ensure().then((core) => core.applyFilters(patch));
    },
    setPage(pageNumber) {
      return this.ensure().then((core) => core.setPage(pageNumber));
    },
    setPageSize(pageSize) {
      return this.ensure().then((core) => core.setPageSize(pageSize));
    },
    retry() {
      return this.ensure().then((core) => core.retry());
    },
    refreshTruth() {
      return this.ensure().then((core) => core.refreshTruth());
    },
    changeStatus(id, status) {
      return this.ensure().then((core) => core.changeStatus(id, status));
    },
  },
});
