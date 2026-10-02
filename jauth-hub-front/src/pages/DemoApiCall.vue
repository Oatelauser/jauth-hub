<!-- /demo API 调用页（B4）：持 access token GET whoamiUri（rs-starter 内省闭环）+ 无 token 对照 401。
     流程照 SSR demo/api-call.html 内联 JS，增量一处：响应按 SimpleResponse.ok 形状解包展示用户视图
     （sub/username/scope/authorities——资源服务器从内省学到的一切），原始报文照旧全文展示。 -->
<script setup>
import { computed, onMounted, ref } from 'vue';
import { getJson } from '../api';
import { t } from '../i18n';
import DemoLayout from '../demo/DemoLayout.vue';
import { createHttpLog, fetchLogged, prettyJson } from '../demo/httplog';
import { readTokens } from '../demo/flow';

const state = ref(null);
const error = ref('');
const tokens = ref(readTokens());
const result = ref(null);
const log = createHttpLog();

document.title = t('demo.api.title') + ' · jauth-hub';

onMounted(async () => {
  try {
    state.value = await getJson('/api/demo/config');
  } catch (e) {
    error.value = e.message;
  }
});

// SimpleResponse.ok 解包：{code,message,data}，00000 时 data 即用户视图；其余（含 401 空体）无视图
const userView = computed(() => {
  if (!result.value) {
    return null;
  }
  try {
    const parsed = JSON.parse(result.value.text);
    return parsed && parsed.code === '00000' ? parsed.data : null;
  } catch {
    return null;
  }
});

const bodyPretty = computed(() => (result.value ? prettyJson(result.value.text) : ''));

async function call(bare) {
  const cfg = state.value.demoConfig;
  const headers = bare ? {} : { Authorization: 'Bearer ' + tokens.value.access_token };
  result.value = await fetchLogged(log, 'GET', cfg.whoamiUri, { headers });
}
</script>

<template>
  <DemoLayout :step="4" :log="log" :title="t('demo.api.title')" :teach="t('demo.api.teach')"
    :educational="state ? state.educational : true">
    <p class="alert error" v-if="error" role="alert">{{ error }}</p>
    <p class="alert warn" v-if="!tokens">{{ t('demo.api.need-token') }}</p>
    <template v-if="tokens">
      <div class="demo-actions">
        <button class="btn primary" type="button" @click="call(false)" :disabled="!state">
          {{ t('demo.api.call') }}
        </button>
        <button class="btn" type="button" @click="call(true)" :disabled="!state">
          {{ t('demo.api.call-bare') }}
        </button>
      </div>
      <h2 class="demo-section-title">{{ t('demo.api.response-title') }}</h2>
      <p>
        <span class="demo-status" v-if="result" :class="result.status < 400 ? 's2xx' : 's4xx'">
          HTTP {{ result.status }}
        </span>
      </p>
      <table class="demo-kv" v-if="userView">
        <tbody>
          <tr><th>sub</th><td>{{ userView.sub || '—' }}</td></tr>
          <tr><th>username</th><td>{{ userView.username || '—' }}</td></tr>
          <tr><th>scope</th><td>{{ userView.scope || '—' }}</td></tr>
          <tr>
            <th>authorities</th>
            <td>{{ (userView.authorities || []).join(' ') || '—' }}</td>
          </tr>
        </tbody>
      </table>
      <pre class="demo-json" v-if="result">{{ bodyPretty }}</pre>
    </template>
  </DemoLayout>
</template>
