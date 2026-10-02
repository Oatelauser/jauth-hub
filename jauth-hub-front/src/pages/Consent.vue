<script setup>
import { computed, onMounted, ref } from 'vue';
import { getJson } from '../api';
import { flowSteps, t } from '../i18n';

const steps = flowSteps('authcode');
const state = ref(null);
const error = ref('');

document.title = t('consent.title') + ' · jauth-hub';

// 引导态/选择器态不出 scope 表单（org 上下文未定，提交无意义），仅保留拒绝出口
const formReady = computed(
  () => state.value && !state.value.orgGuide && !(state.value.orgChoices || []).length
);

onMounted(async () => {
  // 参数与 SSR 页逐一同名透传（client_id/state/org 单值，scope 多值逐个保留）
  const qs = new URLSearchParams(window.location.search);
  const params = new URLSearchParams();
  for (const key of ['client_id', 'state', 'org']) {
    const value = qs.get(key);
    if (value !== null) params.set(key, value);
  }
  qs.getAll('scope').forEach((s) => params.append('scope', s));
  try {
    state.value = await getJson('/api/consent' + (params.toString() ? '?' + params.toString() : ''));
  } catch (e) {
    error.value = e.message;
  }
});

// 选择器重入链接：保留本页现有参数改挂 org，重入本 SPA 页（等价 SSR choice.href 的整页重入语义）
function orgHref(choice) {
  const qs = new URLSearchParams(window.location.search);
  qs.set('org', choice.orgId);
  return '/front/consent?' + qs.toString();
}
</script>

<template>
  <div class="page">
    <aside class="sidebar" v-if="state && state.educational">
      <section class="panel">
        <h2>{{ t('teach.flowTitle') }}</h2>
        <nav class="steps" aria-label="flow">
          <ol>
            <li v-for="(step, i) in steps" :key="i" :class="{ active: i === 1 }">{{ step }}</li>
          </ol>
        </nav>
      </section>
      <section class="panel httplog">
        <h2>{{ t('teach.httplogTitle') }}</h2>
        <p class="placeholder">{{ t('teach.httplogPlaceholder') }}</p>
      </section>
    </aside>
    <main class="card">
      <p class="brand">{{ t('brand') }}</p>
      <h1>{{ t('consent.title') }}</h1>
      <p class="alert" v-if="error">{{ error }}</p>
      <template v-if="state">
        <p class="client-line">
          <span>{{ t('consent.clientLabel') }}</span>：<strong>{{ state.clientName }}</strong>
        </p>
        <p class="org-badge" v-if="state.orgBadge">
          <span>{{ t('consent.orgContext') }}</span>：<strong>{{ state.orgBadge }}</strong>
        </p>
        <details class="teach" v-if="state.educational">
          <summary>{{ t('teach.whatHappened') }}</summary>
          <p>{{ t('consent.teach') }}</p>
        </details>
        <div class="org-guide" v-if="state.orgGuide">{{ t('consent.orgGuide') }}</div>
        <div class="org-select" v-if="(state.orgChoices || []).length">
          <p class="scopes-label">{{ t('consent.orgSelect') }}</p>
          <ul class="org-choices">
            <li v-for="choice in state.orgChoices" :key="choice.orgId">
              <a class="org-choice" :href="orgHref(choice)">{{ choice.orgName }}</a>
            </li>
          </ul>
        </div>
        <!-- 原生表单 POST 浏览器导航（fetch 不会带着授权码 302 到 RP 回调），字段照 SSR consent.html -->
        <form class="form" action="/oauth2/authorize" method="post">
          <input type="hidden" name="_csrf" v-if="state.csrfToken" :value="state.csrfToken" />
          <input type="hidden" name="client_id" :value="state.clientId" />
          <input type="hidden" name="state" :value="state.state" />
          <template v-if="formReady">
            <p class="scopes-label">{{ t('consent.requested') }}</p>
            <ul class="scopes">
              <li v-for="scope in state.scopes" :key="scope.name">
                <label class="scope-item" :class="{ disabled: !scope.grantable }">
                  <input
                    type="checkbox"
                    name="scope"
                    :value="scope.name"
                    :checked="scope.checked"
                    :disabled="!scope.grantable"
                  />
                  <span class="scope-name">{{ scope.name }}</span>
                  <span class="scope-granted" v-if="scope.alreadyGranted">{{ t('consent.granted') }}</span>
                  <span class="scope-desc">{{ scope.description }}</span>
                </label>
              </li>
            </ul>
          </template>
          <div class="actions">
            <button class="btn primary" type="submit" name="action" value="authorize" v-if="formReady">
              {{ t('consent.authorize') }}
            </button>
            <button class="btn" type="submit" name="action" value="deny">{{ t('consent.deny') }}</button>
          </div>
        </form>
      </template>
    </main>
  </div>
</template>
