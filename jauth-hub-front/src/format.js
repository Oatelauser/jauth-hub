// ISO-8601（Instant.toString / 状态面日期字段）→ 本地时区 'yyyy-MM-dd HH:mm'，
// 对齐 SSR #temporals.format 的展示口径；空值出 '—'（SSR 三元同款）。
export function fmtDateTime(value) {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  const pad = (n) => String(n).padStart(2, '0');
  return (
    date.getFullYear() +
    '-' +
    pad(date.getMonth() + 1) +
    '-' +
    pad(date.getDate()) +
    ' ' +
    pad(date.getHours()) +
    ':' +
    pad(date.getMinutes())
  );
}
