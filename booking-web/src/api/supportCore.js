const ID_PATTERN = /^[1-9]\d*$/;
const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;
const TIMESTAMP_PATTERN = /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/;

export function requireId(value, name = 'id') {
  if (typeof value !== 'string' || !ID_PATTERN.test(value)) {
    throw new TypeError(`${name} 必须是非零十进制字符串`);
  }
  return value;
}

export function requireDate(value, name) {
  if (typeof value !== 'string' || !DATE_PATTERN.test(value)) {
    throw new TypeError(`${name} 必须是 yyyy-MM-dd`);
  }
  const parsed = new Date(`${value}T00:00:00Z`);
  if (Number.isNaN(parsed.valueOf()) || parsed.toISOString().slice(0, 10) !== value) {
    throw new TypeError(`${name} 不是有效日期`);
  }
  return value;
}

function pageInteger(value, name, minimum, maximum = Number.MAX_SAFE_INTEGER) {
  if (!Number.isInteger(value) || value < minimum || value > maximum) {
    throw new TypeError(`PageResult ${name} 非法`);
  }
  return value;
}

export function mapNotification(record) {
  if (!record || typeof record !== 'object' || Array.isArray(record)) {
    throw new TypeError('NotificationView 记录格式无效');
  }
  requireId(record.id);
  requireId(record.userId, 'userId');
  if (record.bizId !== null) requireId(record.bizId, 'bizId');
  for (const field of ['title', 'content', 'type']) {
    if (typeof record[field] !== 'string' || !record[field].trim()) {
      throw new TypeError(`NotificationView ${field} 非法`);
    }
  }
  if (![0, 1].includes(record.isRead) || !TIMESTAMP_PATTERN.test(record.createdAt || '')) {
    throw new TypeError('NotificationView 状态或时间非法');
  }
  return { ...record, isRead: record.isRead === 1 };
}

export function mapNotificationPage(data) {
  if (!data || typeof data !== 'object' || !Array.isArray(data.records)) {
    throw new TypeError('通知 PageResult 格式无效');
  }
  return {
    records: data.records.map(mapNotification),
    pageNumber: pageInteger(data.pageNumber, 'pageNumber', 1),
    pageSize: pageInteger(data.pageSize, 'pageSize', 1, 100),
    total: pageInteger(data.total, 'total', 0),
  };
}

export function mapResourceStatistics(data) {
  requireDate(data?.fromDate, 'fromDate');
  requireDate(data?.toDate, 'toDate');
  if (!Array.isArray(data.records)) throw new TypeError('资源统计 records 非法');
  return {
    ...data,
    records: data.records.map((record) => {
      requireId(record?.resourceId, 'resourceId');
      if (typeof record.resourceName !== 'string') throw new TypeError('resourceName 非法');
      for (const field of ['bookingCount', 'completedCount', 'cancelledCount', 'noShowCount', 'occupiedSlotMinutes']) {
        if (!Number.isInteger(record[field]) || record[field] < 0) throw new TypeError(`${field} 非法`);
      }
      if (record.usageRate !== null && (!Number.isFinite(Number(record.usageRate))
          || Number(record.usageRate) < 0 || Number(record.usageRate) > 1)) {
        throw new TypeError('usageRate 非法');
      }
      return record;
    }),
  };
}

export function mapBookingStatistics(data) {
  requireDate(data?.fromDate, 'fromDate');
  requireDate(data?.toDate, 'toDate');
  if (!Array.isArray(data.records)) throw new TypeError('预约统计 records 非法');
  return {
    ...data,
    records: data.records.map((record) => {
      if (typeof record?.status !== 'string' || !record.status
          || !Number.isInteger(record.count) || record.count < 0) {
        throw new TypeError('预约统计记录非法');
      }
      return record;
    }),
  };
}

export function mapSupportError(error) {
  const status = error?.response?.status;
  const code = error?.code ?? error?.response?.data?.code;
  const messages = {
    40000: '请求参数无效',
    40100: '登录已失效',
    40300: '无权限访问',
    40400: '功能未启用或记录不存在',
  };
  error.supportMessage = messages[code]
    || ({ 400: '请求参数无效', 401: '登录已失效', 403: '无权限访问', 404: '功能未启用或记录不存在' })[status]
    || error.message
    || '请求失败';
  return error;
}

export function dateRange(fromDate, toDate) {
  requireDate(fromDate, 'fromDate');
  requireDate(toDate, 'toDate');
  if (fromDate > toDate) throw new RangeError('开始日期不能晚于结束日期');
  return { fromDate, toDate };
}
