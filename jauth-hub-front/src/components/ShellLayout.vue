<!-- 自助面导航壳（B2b §1）：左侧导航（品牌区 + 导航项）+ 顶栏（用户名 + 退出），子路由渲染进
     内容区。导航只挂本批存在的五页（profile/admin 归 B3，demo 归 B4，不预挂）。
     认证守卫不自建：挂载即 GET /api/profile，未认证 401 由 api.js 分流整页跳登录（YAGNI）。 -->
<script setup>
import { onMounted, ref } from 'vue';
import { RouterLink, RouterView } from 'vue-router';
import { getJson } from '../api';
import { t } from '../i18n';

const profile = ref(null);

const navItems = [
  { to: '/selfservice/apps', key: 'nav.apps' },
  { to: '/selfservice/pat', key: 'nav.pat' },
  { to: '/selfservice/my-apps', key: 'nav.my-apps' },
  { to: '/selfservice/my-orgs', key: 'nav.my-orgs' },
  { to: '/selfservice/passkey', key: 'nav.passkey' },
];

onMounted(async () => {
  try {
    profile.value = await getJson('/api/profile');
  } catch {
    // 401 已由 wrapper 整页分流登录；其余失败静默（顶栏用户名空、退出仍可用）
  }
});
</script>

<template>
  <div class="shell">
    <aside class="shell-side">
      <p class="shell-brand">{{ t('brand') }}</p>
      <nav class="shell-nav">
        <RouterLink v-for="item in navItems" :key="item.to" :to="item.to">{{ t(item.key) }}</RouterLink>
      </nav>
    </aside>
    <div class="shell-main">
      <header class="shell-top">
        <span class="shell-user">{{ profile ? profile.username : '' }}</span>
        <!-- 退出走原生表单 POST /logout（带 _csrf 参数，值取 /api/profile 状态面的 csrf 字段）：
             登出是会话级导航，fetch 拿不到 302 落点 -->
        <form method="post" action="/logout">
          <input type="hidden" name="_csrf" v-if="profile && profile.csrfToken" :value="profile.csrfToken" />
          <button class="btn secondary" type="submit">{{ t('nav.logout') }}</button>
        </form>
      </header>
      <main class="shell-body">
        <RouterView />
      </main>
    </div>
  </div>
</template>
