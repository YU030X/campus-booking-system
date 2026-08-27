const codepoints = (value) => [...String(value)].length;
const text = (value) => (typeof value === 'string' ? value.trim() : value);
const decimal = (value) => /^\d+$/.test(String(value));
const integer = (value) => /^-?\d+$/.test(String(value));
const result = (value, errors) => ({ valid: errors.length === 0, errors, value });
const blankNull = (value, limit) => {
  if (value == null) return null;
  const normalized = text(value);
  if (normalized === '') return null;
  return codepoints(normalized) <= limit ? normalized : value;
};

export const normalizeText = (value) => (typeof value === 'string' ? value.trim() : value);

export const normalizeCategoryPayload = (input = {}) => ({
  ...input,
  name: normalizeText(input.name),
  parentId: input.parentId == null || input.parentId === '' ? '0' : String(input.parentId),
  sortOrder: input.sortOrder == null || input.sortOrder === '' ? 0 : Number(input.sortOrder),
  icon: blankNull(input.icon, 255),
});

export const validateCategory = (input = {}) => {
  const value = normalizeCategoryPayload(input);
  const errors = [];
  if (typeof value.name !== 'string' || value.name.length < 1 || codepoints(value.name) > 50) errors.push('分类名称需为1-50字符');
  if (!decimal(value.parentId)) errors.push('父分类ID必须为十进制字符串');
  if (!integer(value.sortOrder) || !Number.isInteger(value.sortOrder) || value.sortOrder < -100000 || value.sortOrder > 100000) errors.push('排序必须为-100000至100000整数');
  if (value.icon != null && codepoints(value.icon) > 255) errors.push('图标不能超过255字符');
  return result(value, errors);
};

export const normalizeResourcePayload = (input = {}) => ({
  ...input,
  categoryId: input.categoryId == null ? input.categoryId : String(input.categoryId),
  name: normalizeText(input.name),
  location: blankNull(input.location, 200),
  capacity: input.capacity === '' || input.capacity == null ? null : Number(input.capacity),
  description: typeof input.description === 'string' && input.description.trim() === '' ? null : input.description,
  images: blankNull(input.images, 1000),
  needApproval: input.needApproval === true || input.needApproval === false
    ? input.needApproval
    : input.needApproval === 1 || input.needApproval === '1'
      ? true
      : input.needApproval === 0 || input.needApproval === '0'
        ? false
        : input.needApproval,
  status: input.status == null || input.status === '' ? 0 : Number(input.status),
  maxAdvanceDays: input.maxAdvanceDays == null || input.maxAdvanceDays === '' ? 0 : Number(input.maxAdvanceDays),
  minDurationMinutes: input.minDurationMinutes == null || input.minDurationMinutes === '' ? null : Number(input.minDurationMinutes),
  maxDurationMinutes: input.maxDurationMinutes == null || input.maxDurationMinutes === '' ? null : Number(input.maxDurationMinutes),
});

export const validateResource = (input = {}) => {
  const value = normalizeResourcePayload(input);
  const errors = [];
  if (!decimal(value.categoryId) || Number(value.categoryId) <= 0) errors.push('分类ID必须为正十进制字符串');
  if (typeof value.name !== 'string' || value.name.length < 1 || codepoints(value.name) > 100) errors.push('资源名称需为1-100字符');
  if (value.location != null && codepoints(value.location) > 200) errors.push('位置不能超过200字符');
  if (value.capacity != null && (!Number.isInteger(value.capacity) || value.capacity <= 0)) errors.push('容量必须为正整数');
  if (value.description != null && codepoints(value.description) > 10000) errors.push('描述不能超过10000字符');
  if (value.images != null && codepoints(value.images) > 1000) errors.push('图片不能超过1000字符');
  if (![true, false].includes(value.needApproval)) errors.push('是否需要审批必须为布尔值或0/1');
  if (![0, 1, 2].includes(value.status)) errors.push('状态必须为0、1或2');
  if (!Number.isInteger(value.maxAdvanceDays) || value.maxAdvanceDays < 0 || value.maxAdvanceDays > 365) errors.push('提前天数必须为0-365');
  if (value.minDurationMinutes != null && (!Number.isInteger(value.minDurationMinutes) || value.minDurationMinutes <= 0 || value.minDurationMinutes % 30 !== 0)) errors.push('最短时长必须为正的30分钟倍数');
  if (value.maxDurationMinutes != null && (!Number.isInteger(value.maxDurationMinutes) || value.maxDurationMinutes <= 0 || value.maxDurationMinutes % 30 !== 0)) errors.push('最长时长必须为正的30分钟倍数');
  if (value.minDurationMinutes != null && value.maxDurationMinutes != null && value.minDurationMinutes > value.maxDurationMinutes) errors.push('最短时长不能大于最长时长');
  return result(value, errors);
};

const validTime = (time) => typeof time === 'string' && /^(?:[01]\d|2[0-3]):(?:00|30):00$/.test(time);
const overlap = (a, b) => a.startTime < b.endTime && a.endTime > b.startTime;

export const normalizeRules = (input = []) => Array.isArray(input) ? input.map((rule) => ({ ...rule, dayOfWeek: Number(rule.dayOfWeek), startTime: text(rule.startTime), endTime: text(rule.endTime) })).sort((a, b) => a.dayOfWeek - b.dayOfWeek || a.startTime.localeCompare(b.startTime)) : input;

export const validateRules = (input = []) => {
  const value = normalizeRules(input);
  const errors = [];
  if (!Array.isArray(value)) return result(value, ['规则必须为数组']);
  const days = {};
  value.forEach((rule) => {
    if (![1, 2, 3, 4, 5, 6, 7].includes(rule.dayOfWeek)) errors.push('星期必须为1-7');
    if (!validTime(rule.startTime) || !validTime(rule.endTime) || rule.startTime >= rule.endTime) errors.push('时间规则无效');
    const prior = days[rule.dayOfWeek] || [];
    if (prior.some((item) => overlap(item, rule))) errors.push('时间规则不能重叠');
    prior.push(rule); days[rule.dayOfWeek] = prior;
  });
  return result(value, errors);
};

const validDate = (date) => {
  if (typeof date !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(date)) return false;
  const [year, month, day] = date.split('-').map(Number);
  const parsed = new Date(Date.UTC(year, month - 1, day));
  return parsed.getUTCFullYear() === year && parsed.getUTCMonth() === month - 1 && parsed.getUTCDate() === day;
};

export const normalizeClosure = (input = {}) => ({ ...input, closureDate: text(input.closureDate), reason: blankNull(input.reason, 200) });

export const validateClosure = (input = {}) => {
  const value = normalizeClosure(input);
  const errors = [];
  if (!validDate(value.closureDate)) errors.push('停用日期必须为有效的yyyy-MM-dd日期');
  if (value.reason != null && codepoints(value.reason) > 200) errors.push('停用原因不能超过200字符');
  return result(value, errors);
};
