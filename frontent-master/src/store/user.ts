import { StoreOptions } from "vuex";
import ACCESS_ENUM from "@/access/accessEnum";
import * as authApi from "@/api/auth";
import { LoginUserVO } from "@/api/auth";
import { clearAccessToken, getAccessToken } from "@/utils/token";

const ANONYMOUS_USER: LoginUserVO = {
  userName: "未登录",
  userRole: ACCESS_ENUM.NOT_LOGIN,
};

export default {
  namespaced: true,
  state: () => ({
    loginUser: { ...ANONYMOUS_USER } as LoginUserVO,
  }),
  actions: {
    /**
     * 拉取当前登录用户。
     * 本地没有 access token 时不再白跑一次请求，直接判定未登录。
     */
    async getLoginUser({ commit }) {
      if (!getAccessToken()) {
        commit("updateUser", { ...ANONYMOUS_USER });
        return;
      }
      try {
        const data = await authApi.getLoginUser();
        commit("updateUser", {
          ...data,
          userRole: data.userRole ?? ACCESS_ENUM.USER,
        });
      } catch {
        commit("updateUser", { ...ANONYMOUS_USER });
      }
    },
    /**
     * 退出登录：后端吊销 refresh token 并拉黑 access token，前端清本地状态。
     */
    async userLogout({ commit }) {
      try {
        await authApi.logout();
      } finally {
        clearAccessToken();
        commit("updateUser", { ...ANONYMOUS_USER });
      }
    },
  },
  mutations: {
    updateUser(state, payload) {
      state.loginUser = payload;
    },
  },
} as StoreOptions<any>;
