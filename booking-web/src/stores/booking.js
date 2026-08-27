import { defineStore } from 'pinia';
import { getBookingApi } from '../api/booking.js';
import { classifyBookingError } from '../components/booking/errors.js';

let apiProvider = getBookingApi;
export const setBookingApiProvider = (provider = getBookingApi) => {
  apiProvider = provider;
};

const requestState = (data = null) => ({
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

const succeeded = (data, empty = false) => ({
  status: empty ? 'empty' : 'success',
  data,
  error: null,
  pending: false,
});

const failed = (current, error) => ({
  ...current,
  status: 'error',
  error,
  pending: false,
});

const mapError = (error) => {
  if (error instanceof TypeError) {
    return { kind: 'INVALID_INPUT', userMessage: error.message, cause: error };
  }
  return { ...classifyBookingError(error), cause: error };
};

export const useBookingStore = defineStore('booking', {
  state: () => ({
    list: requestState(null),
    detail: requestState(null),
    availability: requestState(null),
    create: requestState(null),
    cancel: requestState(null),
    pageResult: { records: [], pageNumber: 1, pageSize: 10, total: 0 },
    filters: { status: '' },
    availabilityKey: null,
    pending: {},
    listSequence: 0,
    detailSequence: 0,
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
        pageNumber: this.pageResult.pageNumber,
        pageSize: this.pageResult.pageSize,
        status: this.filters.status || undefined,
        ...overrides,
      };
      const sequence = ++this.listSequence;
      const key = options.force ? `list:${sequence}` : `list:${JSON.stringify(params)}`;
      return this.dedup(key, async () => {
        this.list = loading(this.list);
        try {
          const api = await apiProvider();
          const data = await api.list(params);
          if (sequence !== this.listSequence) return data;
          this.pageResult = data;
          this.list = succeeded(data, data.records.length === 0);
          return data;
        } catch (error) {
          const mapped = mapError(error);
          if (sequence === this.listSequence) this.list = failed(this.list, mapped);
          throw mapped;
        }
      });
    },

    async fetchDetail(id, options = {}) {
      const sequence = ++this.detailSequence;
      const key = options.force ? `detail:${id}:${sequence}` : `detail:${id}`;
      return this.dedup(key, async () => {
        this.detail = loading(this.detail);
        try {
          const api = await apiProvider();
          const data = await api.detail(id);
          if (sequence === this.detailSequence) this.detail = succeeded(data);
          return data;
        } catch (error) {
          const mapped = mapError(error);
          if (sequence === this.detailSequence) this.detail = failed(this.detail, mapped);
          throw mapped;
        }
      });
    },

    async fetchAvailability(resourceId, date, options = {}) {
      const scope = `${resourceId}:${date}`;
      const key = options.force ? `availability:${scope}:${Date.now()}` : `availability:${scope}`;
      this.availabilityKey = scope;
      return this.dedup(key, async () => {
        this.availability = loading(this.availability);
        try {
          const api = await apiProvider();
          const data = await api.availability(resourceId, date);
          if (this.availabilityKey === scope) this.availability = succeeded(data, data.slots.length === 0);
          return data;
        } catch (error) {
          const mapped = mapError(error);
          if (this.availabilityKey === scope) this.availability = failed(this.availability, mapped);
          throw mapped;
        }
      });
    },

    async createBooking(payload) {
      return this.dedup('booking:create', async () => {
        this.create = loading(this.create);
        try {
          const api = await apiProvider();
          const data = await api.create(payload);
          this.create = succeeded(data);
          await Promise.allSettled([
            this.fetchList({}, { force: true }),
            this.fetchAvailability(payload.resourceId, payload.startTime.slice(0, 10), { force: true }),
          ]);
          return data;
        } catch (error) {
          const mapped = mapError(error);
          this.create = failed(this.create, mapped);
          if (mapped.refreshSlots) {
            await this.fetchAvailability(
              payload.resourceId,
              payload.startTime.slice(0, 10),
              { force: true },
            ).catch(() => {});
          }
          throw mapped;
        }
      });
    },

    async cancelBooking(id, cancelReason = null) {
      return this.dedup(`booking:cancel:${id}`, async () => {
        this.cancel = loading(this.cancel);
        try {
          const api = await apiProvider();
          const data = await api.cancel(id, cancelReason);
          this.cancel = succeeded(data);
          if (String(this.detail.data?.id) === String(id)) this.detail = succeeded(data);
          const refreshes = [this.fetchList({}, { force: true })];
          if (data.resourceId && data.startTime) {
            refreshes.push(this.fetchAvailability(
              data.resourceId,
              data.startTime.slice(0, 10),
              { force: true },
            ));
          }
          await Promise.allSettled(refreshes);
          return data;
        } catch (error) {
          const mapped = mapError(error);
          this.cancel = failed(this.cancel, mapped);
          throw mapped;
        }
      });
    },

    clearCreateState() {
      this.create = requestState(null);
    },
  },
});
