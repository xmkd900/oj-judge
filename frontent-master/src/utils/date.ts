/**
 * 日期格式化。
 *
 * 之前项目用 moment 干这事，但它有 200KB+ 体积，而这里只用到一个"年-月-日"，
 * 用原生 API 就够了。
 */

/**
 * 格式化成 YYYY-MM-DD
 * @param value 日期字符串 / 时间戳 / Date，空值返回 "-"
 */
export function formatDate(value?: string | number | Date): string {
  if (value === null || value === undefined || value === "") {
    return "-";
  }
  const date = value instanceof Date ? value : new Date(value);
  if (Number.isNaN(date.getTime())) {
    return "-";
  }
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, "0");
  const d = String(date.getDate()).padStart(2, "0");
  return `${y}-${m}-${d}`;
}

/**
 * 格式化成 YYYY-MM-DD HH:mm
 */
export function formatDateTime(value?: string | number | Date): string {
  if (value === null || value === undefined || value === "") {
    return "-";
  }
  const date = value instanceof Date ? value : new Date(value);
  if (Number.isNaN(date.getTime())) {
    return "-";
  }
  const hh = String(date.getHours()).padStart(2, "0");
  const mm = String(date.getMinutes()).padStart(2, "0");
  return `${formatDate(date)} ${hh}:${mm}`;
}
