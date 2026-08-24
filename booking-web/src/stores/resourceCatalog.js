import { defineStore } from 'pinia';
import { resourceApi } from '../api/resourceCatalog';

const idle = (data = null) => ({
  status: 'idle',
  data,
  error: null,
  pending: false,
});

const loading = (current) => ({
  ...current,
  status: 'loading',
  error: null,
  pending: true,
});

const failed = (current, error) => ({
  ...current,
  status: 'error',
  error,
  pending: false,
});

const resourceNotFound = (id) => {
  const error = new Error(`资源 ${id} 不存在`);
  error.code = 40400;
  error.userMessage = '资源不存在';
  error.response = { status: 404 };
  return error;
};

export const useResourceCatalogStore = defineStore('resourceCatalog', {
  state: () => ({
    categories: idle([]),
    list: idle(null),
    detail: idle(null),
    filters: { categoryId: '', status: '', keyword: '' },
    pageResult: { records: [], pageNumber: 1, pageSize: 10, total: 0 },
    pending: {},
    rulesByResource: {},
    closureRecords: {},
    listSequence: 0,
  }),

  actions: {
    dedup(key, task) {
      if (this.pending[key]) return this.pending[key];
      const request = Promise.resolve().then(task).finally(() => {
        delete this.pending[key];
      });
      this.pending[key] = request;
      return request;
    },

    async fetchList(overrides = {}, options = {}) {
      const params = {
        ...this.filters,
        pageNumber: this.pageResult.pageNumber,
        pageSize: this.pageResult.pageSize,
        ...overrides,
      };
      Object.keys(params).forEach((key) => {
        if (params[key] === '' || params[key] === undefined || params[key] === null) {
          delete params[key];
        }
      });
      const sequence = ++this.listSequence;
      const key = `list:${JSON.stringify(params)}${options.force ? `:${sequence}` : ''}`;
      return this.dedup(key, async () => {
        this.list = loading(this.list);
        try {
          const data = await resourceApi.list(params);
          if (sequence !== this.listSequence) return data;
          this.pageResult = data || { records: [], pageNumber: 1, pageSize: 10, total: 0 };
          this.list = {
            status: this.pageResult.records?.length ? 'success' : 'empty',
            data: this.pageResult,
            error: null,
            pending: false,
          };
          return data;
        } catch (error) {
          if (sequence === this.listSequence) this.list = failed(this.list, error);
          throw error;
        }
      });
    },

    async fetchDetail(id) {
      return this.dedup(`detail:${String(id)}`, async () => {
        this.detail = loading(this.detail);
        try {
          if (id === 0 || id === '0') throw resourceNotFound(id);
          const data = await resourceApi.detail(id);
          this.detail = { status: 'success', data, error: null, pending: false };
          return data;
        } catch (error) {
          this.detail = failed(this.detail, error);
          throw error;
        }
      });
    },

    async fetchCategories(options = {}) {
      const key = options.force ? `categories:${Date.now()}` : 'categories';
      return this.dedup(key, async () => {
        this.categories = loading(this.categories);
        try {
          const data = await resourceApi.categories();
          this.categories = { status: 'success', data: data || [], error: null, pending: false };
          return data;
        } catch (error) {
          this.categories = failed(this.categories, error);
          throw error;
        }
      });
    },

    async createCategory(body) {
      return this.dedup(`category:create:${JSON.stringify(body)}`, async () => {
        const result = await resourceApi.createCategory(body);
        await this.fetchCategories({ force: true });
        return result;
      });
    },

    async updateCategory(id, body) {
      return this.dedup(`category:update:${id}:${JSON.stringify(body)}`, async () => {
        const result = await resourceApi.updateCategory(id, body);
        await this.fetchCategories({ force: true });
        return result;
      });
    },

    async deleteCategory(id) {
      return this.dedup(`category:delete:${id}`, async () => {
        const result = await resourceApi.deleteCategory(id);
        await this.fetchCategories({ force: true });
        return result;
      });
    },

    async create(body) {
      return this.dedup(`resource:create:${JSON.stringify(body)}`, () => resourceApi.create(body));
    },

    async update(id, body) {
      return this.dedup(`resource:update:${id}:${JSON.stringify(body)}`, async () => {
        const result = await resourceApi.update(id, body);
        this.detail = { ...this.detail, data: result || this.detail.data };
        return result;
      });
    },

    async updateStatus(id, status) {
      return this.dedup(`resource:status:${id}:${status}`, async () => {
        const result = await resourceApi.status(id, status);
        this.detail = { ...this.detail, data: result || this.detail.data };
        return result;
      });
    },

    async replaceRules(id, rules) {
      return this.dedup(`rules:${id}:${JSON.stringify(rules)}`, async () => {
        const result = await resourceApi.replaceRules(id, rules);
        this.rulesByResource[String(id)] = result || [];
        if (this.detail.data) this.detail.data = { ...this.detail.data, timeRules: result || [] };
        return result;
      });
    },

    async addClosure(id, body) {
      return this.dedup(`closure:add:${id}:${JSON.stringify(body)}`, async () => {
        const result = await resourceApi.addClosure(id, body);
        const scope = String(id);
        const existing = this.closureRecords[scope] || [];
        const recordId = String(result?.id ?? result?.closureId ?? body?.id ?? '');
        const index = existing.findIndex((item) => String(item.id ?? item.closureId) === recordId);
        if (index >= 0) existing.splice(index, 1, result);
        else existing.push(result);
        this.closureRecords[scope] = existing;
        return result;
      });
    },

    async deleteClosure(id, closureId) {
      return this.dedup(`closure:delete:${id}:${closureId}`, async () => {
        const result = await resourceApi.deleteClosure(id, closureId);
        const scope = String(id);
        this.closureRecords[scope] = (this.closureRecords[scope] || [])
          .filter((item) => String(item.id ?? item.closureId) !== String(closureId));
        return result;
      });
    },
  },
});
