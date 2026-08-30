import { defineStore } from 'pinia';
import { reactive } from 'vue';
import { createAdminApprovalsCore, createAdminApprovalsState, openAdminApprovalsApi } from '../api/adminApprovals';

export const useAdminApprovalsStore = defineStore('adminApprovals', {
  state: () => ({
    core: null,
  }),

  getters: {
    page: (state) => state.core?.state.page ?? { phase: 'idle', records: [], total: 0 },
    selected: (state) => state.core?.state.selected ?? { phase: 'none', booking: null },
    pageNumber: (state) => state.core?.state.pageNumber ?? 1,
    pageSize: (state) => state.core?.state.pageSize ?? 10,
    actions: (state) => state.core?.state.actions ?? {},
  },

  actions: {
    async ensure() {
      if (!this.core) {
        this.core = createAdminApprovalsCore(await openAdminApprovalsApi(), reactive(createAdminApprovalsState()));
      }
      return this.core;
    },
    fetchList(options = {}) {
      return this.ensure().then((core) => core.fetchList(options));
    },
    selectFrom(row) {
      return this.ensure().then((core) => core.setSelectedFrom(row));
    },
    clearSelection() {
      return this.ensure().then((core) => core.clearSelection());
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
    requestAction(id, action, comment) {
      return this.ensure().then((core) => core.requestAction(id, action, comment));
    },
  },
});
