import router from "@/router";
import { setSessionExpiredHandler } from "@/api/request";

/**
 * 会话彻底失效（access token 过期且 refresh token 也失效）时的兜底动作：
 * 带上当前地址跳登录页，登录成功后原路返回。
 *
 * 注册在这里而不是在 request.ts 里直接 import router，
 * 是为了避免 request → router → view → request 的循环依赖。
 */
setSessionExpiredHandler(() => {
  const current = router.currentRoute.value;
  if (current.path.startsWith("/user/login")) {
    return;
  }
  router.push({
    path: "/user/login",
    query: { redirect: current.fullPath },
  });
});
