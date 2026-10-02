<!-- /demo 首页（B4）：说明 + 开始授权。流程逐行照 SSR demo/index.html 内联 JS——verifier(48B)/
     state(16B) 生成存 sessionStorage，SHA-256 派生 S256 challenge，拼 authorize URL 后顶层导航
     （离开 SPA 进认证中心，回调由 /front/demo/callback 接回）。配置来自 GET /api/demo/config（无 csrf
     公开面，B1c）。 -->
<script setup>
import { onMounted, ref } from 'vue';
import { getJson } from '../api';
import { t } from '../i18n';
import DemoLayout from '../demo/DemoLayout.vue';
import { createHttpLog, logEntry } from '../demo/httplog';
import { randomB64Url, saveVerifierAndState, sha256B64Url } from '../demo/flow';

const state = ref(null);
const error = ref('');
const log = createHttpLog();

document.title = t('demo.index.title') + ' · jauth-hub';

onMounted(async () => {
  try {
    state.value = await getJson('/api/demo/config');
  } catch (e) {
    error.value = e.message;
  }
});

async function start() {
  const cfg = state.value.demoConfig;
  const verifier = randomB64Url(48);
  const challenge = await sha256B64Url(verifier);
  const demoState = randomB64Url(16);
  saveVerifierAndState(verifier, demoState);
  const url =
    cfg.authorizeEndpoint +
    '?response_type=code' +
    '&client_id=' +
    encodeURIComponent(cfg.clientId) +
    '&redirect_uri=' +
    encodeURIComponent(cfg.redirectUri) +
    '&scope=' +
    encodeURIComponent(cfg.scope) +
    '&state=' +
    encodeURIComponent(demoState) +
    '&code_challenge=' +
    encodeURIComponent(challenge) +
    '&code_challenge_method=S256';
  logEntry(log, 'GET ' + url, 'req');
  window.location.href = url;
}
</script>

<template>
  <DemoLayout :step="1" :log="log" :title="t('demo.index.title')" :teach="t('demo.index.teach')"
    :educational="state ? state.educational : true">
    <p class="alert error" v-if="error" role="alert">{{ error }}</p>
    <template v-if="state">
      <!-- 字典条目含 <strong>（照 SSR th:utext 键义）；内容为本工程静态字典，非运行期数据 -->
      <p class="demo-client-line" v-html="t('demo.index.client-line')"></p>
      <div class="demo-actions">
        <button class="btn primary" type="button" @click="start">{{ t('demo.index.start') }}</button>
      </div>
      <p class="demo-hint">/oauth2/authorize · PKCE S256 · state</p>
    </template>
  </DemoLayout>
</template>
