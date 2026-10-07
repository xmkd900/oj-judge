/**
 * Access Token 存储：内存为主，localStorage 兜底。
 *
 * 内存变量保证同一个标签页里读写最快、关闭即销毁；
 * localStorage 的作用是刷新页面（F5）后不用重新登录。
 * Refresh Token 不在这里——它在 HttpOnly Cookie 里，JS 本来就读不到。
 */

const TOKEN_KEY = "oj_access_token";
const EXPIRES_AT_KEY = "oj_access_token_expires_at";

/** 内存副本 */
let memoryToken: string | null = null;
let memoryExpiresAt = 0;

function readLocal(): { token: string | null; expiresAt: number } {
  try {
    const token = localStorage.getItem(TOKEN_KEY);
    const expiresAt = Number(localStorage.getItem(EXPIRES_AT_KEY) ?? 0);
    return { token: token || null, expiresAt };
  } catch {
    return { token: null, expiresAt: 0 };
  }
}

export function getAccessToken(): string | null {
  if (memoryToken) {
    return memoryToken;
  }
  const { token, expiresAt } = readLocal();
  if (token) {
    memoryToken = token;
    memoryExpiresAt = expiresAt;
  }
  return memoryToken;
}

/**
 * 保存令牌
 * @param token access token
 * @param expiresIn 有效期（秒）
 */
export function setAccessToken(token: string, expiresIn?: number): void {
  memoryToken = token;
  memoryExpiresAt = expiresIn ? Date.now() + expiresIn * 1000 : 0;
  try {
    localStorage.setItem(TOKEN_KEY, token);
    localStorage.setItem(EXPIRES_AT_KEY, String(memoryExpiresAt));
  } catch {
    // 隐私模式下 localStorage 可能不可写，忽略即可，内存副本仍然有效
  }
}

export function clearAccessToken(): void {
  memoryToken = null;
  memoryExpiresAt = 0;
  try {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(EXPIRES_AT_KEY);
  } catch {
    // 同上
  }
}

/**
 * 本地令牌是否已过期（只用于提前刷新，不作为能否请求的依据）
 */
export function isAccessTokenExpired(): boolean {
  if (!memoryExpiresAt) {
    return false;
  }
  // 提前 60 秒判定过期，留出刷新时间
  return Date.now() >= memoryExpiresAt - 60_000;
}
