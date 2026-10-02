<!-- 已授权应用看板（B2b）：授权持久化与撤销语义。行内 revoke 走既有 JSON 端点（授权 + consent
     双清），危险动作必经确认对话框；passkeyEnabled 时给通行密钥入口（窜连 → /selfservice/passkey）。 -->
<script setup>
import { computed, onMounted, ref } from 'vue';
import { RouterLink } from 'vue-router';
import { getJson, postJson } from '../api';
import { fmtDateTime } from '../format';
import { t } from '../i18n';
import ConfirmDialog from '../components/ConfirmDialog.vue';

const state = ref(null);
const error = ref('');
const revoking = ref(null); // 待确认撤销的应用行（null = 对话框关）
const busy = ref(false);

document.title = t('apps.title') + ' · jauth-hub';

onMounted(load);

async function load() {
  try {
    state.value = await getJson('/api/selfservice/apps');
  } catch (e) {
    error.value = e.message;
  }
}

const csrf = computed(() => ({
  csrfToken: state.value ? state.value.csrfToken : null,
  csrfHeaderName: state.value ? state.value.csrfHeaderName : null,
}));

async function revoke() {
  if (!revoking.value || busy.value) return;
  busy.value = true;
  const clientId = revoking.value.clientId;
  try {
    await postJson('/selfservice/apps/' + encodeURIComponent(clientId) + '/revoke', undefined, csrf.value);
    revoking.value = null;
    await load();
  } catch (e) {
    error.value = e.message;
    revoking.value = null;
  }
  busy.value = false;
}
</script>

<template>
  <main class="card card-fluid">
    <h1>{{ t('apps.title') }}</h1>
    <p class="alert error" v-if="error" role="alert">{{ error }}</p>
    <template v-if="state">
      <p class="hint" v-if="state.passkeyEnabled">
        <RouterLink to="/selfservice/passkey">{{ t('apps.passkey-link') }}</RouterLink>
      </p>
      <div class="alert warn" v-if="!state.appsSupported">{{ t('apps.unsupported') }}</div>
      <template v-else>
        <div class="empty" v-if="!state.apps.length"><span class="empty-title">{{ t('apps.empty') }}</span></div>
        <table class="data-table" v-else>
          <thead>
            <tr>
              <th>{{ t('apps.col-app') }}</th>
              <th>{{ t('apps.col-scopes') }}</th>
              <th>{{ t('apps.col-authorized-at') }}</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="app in state.apps" :key="app.clientId">
              <td>{{ app.clientName }}</td>
              <td>{{ app.scopes.join(' ') }}</td>
              <td>{{ fmtDateTime(app.lastAuthorizedAt) }}</td>
              <td>
                <button class="btn secondary" type="button" @click="revoking = app">{{ t('apps.revoke') }}</button>
              </td>
            </tr>
          </tbody>
        </table>
      </template>
    </template>
    <ConfirmDialog
      v-if="revoking"
      :title="t('apps.revoke')"
      :message="t('apps.revoke-confirm')"
      :confirm-label="t('apps.revoke')"
      :busy="busy"
      @confirm="revoke"
      @cancel="revoking = null"
    />
  </main>
</template>
