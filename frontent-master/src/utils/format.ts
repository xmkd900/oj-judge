/**
 * 判题限制的展示格式化。
 *
 * 后端存的单位：
 *   timeLimit   —— 毫秒（ms）
 *   memoryLimit —— <b>字节</b>（byte），不是 KB 也不是 MB
 *   stackLimit  —— 字节
 *
 * 之前前端直接把数字打出来，于是内存限制显示成 "268435456"，
 * 谁也看不出那是 256MB。这里统一换算成人能读的形式。
 */

const KB = 1024;
const MB = 1024 * 1024;

/**
 * 字节 -> 人类可读。
 * 268435456 -> "256 MB"；正好整除就用整数，否则保留一位小数。
 */
export const formatMemory = (bytes?: number): string => {
  if (bytes === undefined || bytes === null) {
    return "未设置";
  }
  if (bytes >= MB) {
    const mb = bytes / MB;
    return `${Number.isInteger(mb) ? mb : mb.toFixed(1)} MB`;
  }
  if (bytes >= KB) {
    const kb = bytes / KB;
    return `${Number.isInteger(kb) ? kb : kb.toFixed(1)} KB`;
  }
  return `${bytes} B`;
};

/**
 * 毫秒 -> 人类可读。
 * 1000 -> "1000 ms"；2000 -> "2.0 s"
 */
export const formatTime = (ms?: number): string => {
  if (ms === undefined || ms === null) {
    return "未设置";
  }
  if (ms >= 1000) {
    const s = ms / 1000;
    return `${Number.isInteger(s) ? s : s.toFixed(1)} s`;
  }
  return `${ms} ms`;
};

/** MB -> 字节。编辑题时表单填 MB，提交前要转成字节 */
export const mbToBytes = (mb?: number): number | undefined =>
  mb === undefined || mb === null ? undefined : Math.round(mb * MB);

/** 字节 -> MB。加载题目时把字节转成 MB 填进表单 */
export const bytesToMb = (bytes?: number): number | undefined =>
  bytes === undefined || bytes === null ? undefined : bytes / MB;
