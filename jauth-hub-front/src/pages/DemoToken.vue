<!-- /demo 令牌面板（B4）：opaque 令牌展示（折叠/展开）+ 剩余有效期倒计时 + 内省演示。
     流程逐行照 SSR demo/token.html 内联 JS——RFC 7662：token 放 form 体，Basic 携带机密客户端
     凭证（rsClientId/rsClientSecret 与 rs-starter 同源的 dev 教学夹具；生产内省凭证只在资源服务器
     后端，教学文案明示）。 -->
<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue';
import { RouterLink } from 'vue-router';
import { getJson } from '../api';
import { t } from '../i18n';
import DemoLayout from '../demo/DemoLayout.vue';
import { createHttpLog, fetchLogged, prettyJson } from '../demo/httplog';
import { readTokens } from '../demo/flow';

const state = ref(null);
const error = ref('');
const tokens = ref(readTokens());
const masked = ref(true);
const countdown = ref('');
const expired = ref(false);
const introspected = ref(null);
const introspectRaw = ref('');
const log = createHttpLog();

document.title = t('demo.token.title') + ' · jauth-hub';

let countdownTimer = null;

onBeforeUnmount(() => window.clearInterval(countdownTimer));

onMounted(async () => {
  if (!tokens.value) {
    return;
  }
  startCountdown();
  try {
    state.value = await getJson('/api/demo/config');
  } catch (e) {
    error.value = e.message; // 令牌面板仍可看，内省按钮需配置
  }
});

function toggleReveal() {
  masked.value = !masked.value;
}

async function introspect() {
  const cfg = state.value.demoConfig;
  const basic = btoa(cfg.rsClientId + ':' + cfg.rsClientSecret);
  const result = await fetchLogged(log, 'POST', cfg.introspectEndpoint, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
      Authorization: 'Basic ' + basic,
    },
    body: new URLSearchParams({ token: tokens.value.access_token }),
  });
  introspected.value = JSON.parse(result.text);
  introspectRaw.value = prettyJson(result.text);
}

function startCountdown() {
  const expiresAt = tokens.value.obtainedAt + (tokens.value.expires_in || 0) * 1000;
  const tick = () => {
    const remain = Math.floor((expiresAt - Date.now()) / 1000);
    if (remain <= 0) {
      countdown.value = t('demo.token.expired');
      expired.value = true;
      window.clearInterval(countdownTimer);
      return;
    }
    const minutes = Math.floor(remain / 60);
    const seconds = remain % 60;
    countdown.value = minutes + ':' + (seconds < 10 ? '0' : '') + seconds;
  };
  tick();
  countdownTimer = window.setInterval(tick, 1000);
}
</script>

<template>
  <DemoLayout :step="3" :log="log" :title="t('demo.token.title')" :teach="t('demo.token.teach')"
    :educational="state ? state.educational : true">
    <p class="alert error" v-if="error" role="alert">{{ error }}</p>
    <p class="alert warn" v-if="!tokens">{{ t('demo.token.no-token') }}</p>
    <template v-if="tokens">
      <h2 class="demo-section-title">{{ t('demo.token.access-label') }}</h2>
      <p class="demo-token">
        {{ masked ? tokens.access_token.slice(0, 12) + t('demo.token.masked') : tokens.access_token }}
      </p>
      <div class="demo-actions">
        <button class="btn" type="button" @click="toggleReveal">{{ t('demo.token.reveal') }}</button>
      </div>

      <h2 class="demo-section-title">{{ t('demo.token.expires-label') }}</h2>
      <p class="demo-countdown" :class="{ expired }">{{ countdown || '--:--' }}</p>

      <h2 class="demo-section-title">{{ t('demo.token.introspect-title') }}</h2>
      <div class="demo-actions">
        <button class="btn primary" type="button" @click="introspect" :disabled="!state">
          {{ t('demo.token.introspect-submit') }}
        </button>
      </div>
      <table class="demo-kv" v-if="introspected">
        <tbody>
          <tr><th>active</th><td>{{ introspected.active }}</td></tr>
          <tr><th>sub</th><td>{{ introspected.sub || '—' }}</td></tr>
          <tr><th>username</th><td>{{ introspected.username || '—' }}</td></tr>
          <tr><th>scope</th><td>{{ introspected.scope || '—' }}</td></tr>
          <tr><th>client_id</th><td>{{ introspected.client_id || '—' }}</td></tr>
          <tr><th>token_type</th><td>{{ introspected.token_type || '—' }}</td></tr>
          <tr>
            <th>exp</th>
            <td>{{ introspected.exp ? new Date(introspected.exp * 1000).toLocaleString() : '—' }}</td>
          </tr>
        </tbody>
      </table>
      <pre class="demo-json" v-if="introspectRaw">{{ introspectRaw }}</pre>

      <div class="demo-actions">
        <RouterLink class="btn primary" to="/demo/api-call">{{ t('demo.token.go-api') }}</RouterLink>
        <RouterLink class="btn" to="/demo">{{ t('demo.token.restart') }}</RouterLink>
      </div>
    </template>
  </DemoLayout>
</template>
