import router from "@/router";
import store from "@/store";
import ACCESS_ENUM from "@/access/accessEnum";
import checkAccess from "@/access/checkAccess";
import { getAccessToken } from "@/utils/token";

/**
 * 全局路由守卫。
 *
 * 编排顺序：
 *  1. 本地没有 access token，且目标页面需要登录 → 直接去登录页（不浪费一次请求）
 *  2. 有 token 但 store 里还没用户信息 → 拉一次 /get/login 恢复登录态
 *     （这一步若触发 401，request 拦截器会先用 refresh token 续期，续不上才会跳登录）
 *  3. 已登录还去登录/注册页 → 送回首页，避免重复登录
 *  4. 权限不足 → 去 /noAuth
 */
router.beforeEach(async (to, from, next) => {
  const needAccess = (to.meta?.access as string) ?? ACCESS_ENUM.NOT_LOGIN;

  // 1. 无令牌且页面需要登录
  if (needAccess !== ACCESS_ENUM.NOT_LOGIN && !getAccessToken()) {
    next(`/user/login?redirect=${encodeURIComponent(to.fullPath)}`);
    return;
  }

  // 2. 还没加载过真实用户信息，就恢复一次登录态
  //    注意：初始 state 的 userRole 是 "notLogin"（占位值），它同样是"未加载"的一种，
  //    不能只判断 null，否则刷新页面时永远不会去拉用户信息 —— token 在 localStorage 里也没用上
  let loginUser = store.state.user.loginUser;
  const hasToken = !!getAccessToken();
  const notLoaded =
    !loginUser ||
    !loginUser.userRole ||
    (hasToken && loginUser.userRole === ACCESS_ENUM.NOT_LOGIN);
  if (notLoaded) {
    await store.dispatch("user/getLoginUser");
    loginUser = store.state.user.loginUser;
  }

  const role = loginUser?.userRole ?? ACCESS_ENUM.NOT_LOGIN;
  const loggedIn = role !== ACCESS_ENUM.NOT_LOGIN;

  // 3. 已登录就别再进登录/注册页了
  if (
    loggedIn &&
    (to.path === "/user/login" || to.path === "/user/register")
  ) {
    next("/");
    return;
  }

  if (needAccess !== ACCESS_ENUM.NOT_LOGIN) {
    // 令牌失效导致恢复登录态失败
    if (!loggedIn) {
      next(`/user/login?redirect=${encodeURIComponent(to.fullPath)}`);
      return;
    }
    // 已登录但权限不足
    if (!checkAccess(loginUser, needAccess)) {
      next("/noAuth");
      return;
    }
  }
  next();
});
