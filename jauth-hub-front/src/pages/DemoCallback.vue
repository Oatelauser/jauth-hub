<!-- /demo 回调页（B4）：读 code+state → 校验 state（防 CSRF）→ 浏览器直连同源 /oauth2/token 手工
     换令牌（公开客户端 PKCE）→ 令牌入 sessionStorage。流程逐行照 SSR demo/callback.html 内联 JS；
     授权响应导航本身先记入 httplog，错误态（error 参数）如实展示。 -->
<script setup>
import { onMounted, ref } from 'vue';
import { RouterLink } from 'vue-router';
import { getJson } from '../api';
import { t } from '../i18n';
import DemoLayout from '../demo/DemoLayout.vue';
import { createHttpLog, fetchLogged, logEntry } from '../demo/httplog';
import { saveTokens, takeVerifierAndState } from '../demo/flow';

const state = ref(null);
const error = ref('');
const tokens = ref(null);
const log = createHttpLog();

document.title = t('demo.callback.title') + ' · jauth-hub';

onMounted(run);

async function run() {
  try {
    state.value = await getJson('/api/demo/config');
  } catch (e) {
    error.value = e.message;
    return;
  }
  const params = new URLSearchParams(window.location.search);
  // 授权响应本身就是一次真实的浏览器导航，先记入日志再处理（code/state 截短防令牌泄屏）
  logEntry(log, 'GET ' + window.location.pathname + '?' + summarizeQuery(params), 'ok');
  if (params.get('error')) {
    fail(params.get('error') + (params.get('error_description') ? '：' + params.get('error_description') : ''));
    return;
  }
  const code = params.get('code');
  if (!code) {
    fail(t('demo.callback.no-code'));
    return;
  }
  const stored = takeVerifierAndState();
  if (!stored.verifier) {
    fail(t('demo.callback.missing-verifier'));
    return;
  }
  if (stored.state !== params.get('state')) {
    fail(t('demo.callback.state-mismatch'));
    return;
  }
  const cfg = state.value.demoConfig;
  try {
    const result = await fetchLogged(log, 'POST', cfg.tokenEndpoint, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        grant_type: 'authorization_code',
        code: code,
        redirect_uri: cfg.redirectUri,
        client_id: cfg.clientId,
        code_verifier: stored.verifier,
      }),
    });
    if (!result.ok) {
      fail(t('demo.callback.exchange-fail') + ' HTTP ' + result.status + '：' + result.text);
      return;
    }
    const obtained = JSON.parse(result.text);
    saveTokens(obtained);
    tokens.value = obtained;
  } catch {
    fail(log.entries[log.entries.length - 1].summary);
  }
}

function fail(message) {
  error.value = message;
}

function summarizeQuery(params) {
  const parts = [];
  params.forEach((value, key) => {
    parts.push(key + '=' + (key === 'code' || key === 'state' ? value.slice(0, 8) + '…' : value));
  });
  return parts.join('&');
}
</script>

<template>
  <DemoLayout :step="3" :log="log" :title="t('demo.callback.title')" :teach="t('demo.callback.teach')"
    :educational="state ? state.educational : true">
    <p class="alert error" v-if="error" role="alert">{{ error }}</p>
    <table class="demo-kv" v-if="tokens">
      <tbody>
        <tr><th>token_type</th><td>{{ tokens.token_type || '—' }}</td></tr>
        <tr><th>expires_in</th><td>{{ tokens.expires_in }} s</td></tr>
        <tr><th>scope</th><td>{{ tokens.scope || '—' }}</td></tr>
        <tr><th>id_token</th><td>{{ tokens.id_token ? t('demo.callback.has-id-token') : t('demo.callback.none') }}</td></tr>
        <tr><th>refresh_token</th><td>{{ tokens.refresh_token ? t('demo.callback.has-refresh-token') : t('demo.callback.none') }}</td></tr>
      </tbody>
    </table>
    <div class="demo-actions" v-if="tokens">
      <RouterLink class="btn primary" to="/demo/token">{{ t('demo.callback.go-token') }}</RouterLink>
    </div>
  </DemoLayout>
</template>
