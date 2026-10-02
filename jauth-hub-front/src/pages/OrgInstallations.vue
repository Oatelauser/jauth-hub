<!-- 安装审批（B2b，OWNER 面）：双分区（PENDING 待批 + 全量安装行）+ status pill（前端字典
     status-{枚举名}）+ approve/reject/revoke + 面向 org 的安装发起表单。批准时勾选集即 ceiling
     （默认全选，空勾选由服务端 A0511 拒）；撤销必经确认对话框。 -->
<script setup>
import { computed, onMounted, reactive, ref } from 'vue';
import { RouterLink } from 'vue-router';
import { getJson, postJson } from '../api';
import { fmtDateTime } from '../format';
import { t } from '../i18n';
import ConfirmDialog from '../components/ConfirmDialog.vue';

const orgId = (window.location.pathname.split('/')[4] || '');

const state = ref(null);
const error = ref('');
const busy = ref(false);
const revoking = ref(null); // 待确认撤销的安装行
// 每张待批卡的 ceiling 勾选：requestedScopes 默认全选（SSR 同款），按 installationId 持态
const ceilings = reactive({});
const requestForm = reactive({ clientId: '', scopes: [] });

document.title = t('orginst.title') + ' · jauth-hub';

onMounted(load);

async function load() {
  try {
    state.value = await getJson('/api/selfservice/orgs/' + encodeURIComponent(orgId) + '/installations');
    for (const row of state.value.pending) {
      ceilings[row.id] = [...row.requestedScopes]; // 默认全选 = ceiling 收窄前原样
    }
  } catch (e) {
    error.value = e.message; // A0508 非 OWNER：页面错误态，不白屏
  }
}

const csrf = computed(() => ({
  csrfToken: state.value ? state.value.csrfToken : null,
  csrfHeaderName: state.value ? state.value.csrfHeaderName : null,
}));

const base = '/selfservice/orgs/' + encodeURIComponent(orgId) + '/installations/';

async function approve(row) {
  if (busy.value) return;
  busy.value = true;
  error.value = '';
  try {
    await postJson(base + encodeURIComponent(row.id) + '/approve', { scopes: ceilings[row.id] || [] }, csrf.value);
    await load();
  } catch (e) {
    error.value = e.message;
  }
  busy.value = false;
}

async function reject(row) {
  if (busy.value) return;
  busy.value = true;
  error.value = '';
  try {
    await postJson(base + encodeURIComponent(row.id) + '/reject', {}, csrf.value);
    await load();
  } catch (e) {
    error.value = e.message;
  }
  busy.value = false;
}

async function revoke() {
  if (!revoking.value || busy.value) return;
  busy.value = true;
  const id = revoking.value.id;
  try {
    await postJson(base + encodeURIComponent(id) + '/revoke', {}, csrf.value);
    revoking.value = null;
    await load();
  } catch (e) {
    error.value = e.message;
    revoking.value = null;
  }
  busy.value = false;
}

async function requestInstallation() {
  if (busy.value) return;
  busy.value = true;
  error.value = '';
  try {
    await postJson(base, { clientId: requestForm.clientId.trim(), scopes: requestForm.scopes }, csrf.value);
    requestForm.clientId = '';
    requestForm.scopes = [];
    await load();
  } catch (e) {
    error.value = e.message;
  }
  busy.value = false;
}
</script>

<template>
  <main class="card card-fluid">
    <h1>
      {{ t('orginst.title') }}
      <span class="pill pill-accent" v-if="state && state.org">{{ state.org.orgName }}</span>
    </h1>
    <p class="hint"><RouterLink to="/selfservice/my-orgs">{{ t('common.back-my-orgs') }}</RouterLink></p>
    <p class="alert error" v-if="error" role="alert">{{ error }}</p>
    <template v-if="state">
      <div class="alert warn" v-if="!state.installationsSupported">{{ t('orginst.unsupported') }}</div>

      <template v-else>
        <h2>{{ t('orginst.pending-title') }}</h2>
        <p class="hint" v-if="!state.pending.length">{{ t('orginst.pending-empty') }}</p>
        <div class="panel" v-for="row in state.pending" :key="row.id">
          <p>
            <strong>{{ row.clientName }}</strong>
            <span class="cell-mono">{{ row.clientLabel }}</span>
          </p>
          <p class="hint">
            {{ t('orginst.col-requester') }}：{{ row.requestedByName }} · {{ t('orginst.requested-scopes') }}：
            <span class="cell-mono">{{ row.requestedScopes.join(' ') }}</span>
          </p>
          <!-- 勾选集即 ceiling：默认全选，OWNER 可收窄 -->
          <label class="scope-item" v-for="scope in row.requestedScopes" :key="scope">
            <input type="checkbox" v-model="ceilings[row.id]" :value="scope" />
            <span class="scope-name">{{ scope }}</span>
          </label>
          <p class="actions">
            <button class="btn primary" type="button" :disabled="busy" @click="approve(row)">{{ t('orginst.approve') }}</button>
            <button class="btn danger" type="button" :disabled="busy" @click="reject(row)">{{ t('orginst.reject') }}</button>
          </p>
        </div>

        <h2 class="section-gap">{{ t('orginst.all-title') }}</h2>
        <p class="hint" v-if="!state.installations.length">{{ t('orginst.all-empty') }}</p>
        <table class="data-table" v-else>
          <thead>
            <tr>
              <th>{{ t('orginst.col-client') }}</th>
              <th>{{ t('orginst.col-status') }}</th>
              <th>{{ t('orginst.col-ceiling') }}</th>
              <th>{{ t('orginst.col-requester') }}</th>
              <th>{{ t('orginst.col-approver') }}</th>
              <th>{{ t('orginst.col-created') }}</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in state.installations" :key="row.id">
              <td>
                {{ row.clientName }}<br />
                <span class="cell-mono">{{ row.clientLabel }}</span>
              </td>
              <td>
                <span
                  class="pill"
                  :class="{ PENDING: 'pill-muted', APPROVED: 'pill-success', REJECTED: 'pill-danger', REVOKED: 'pill-danger' }[row.status]"
                >
                  {{ t('orginst.status-' + row.status) }}
                </span>
              </td>
              <td class="cell-mono">{{ row.ceilingScopes.length ? row.ceilingScopes.join(' ') : '—' }}</td>
              <td>{{ row.requestedByName || '—' }}</td>
              <td>
                <template v-if="row.approvedByName">{{ row.approvedByName }} {{ fmtDateTime(row.approvedAt) }}</template>
                <template v-else>—</template>
              </td>
              <td>{{ fmtDateTime(row.createdAt) }}</td>
              <td>
                <button class="btn secondary" type="button" v-if="row.status === 'APPROVED'" @click="revoking = row">
                  {{ t('orginst.revoke') }}
                </button>
              </td>
            </tr>
          </tbody>
        </table>

        <h2 class="section-gap">{{ t('orginst.request-title') }}</h2>
        <form class="form" @submit.prevent="requestInstallation">
          <label class="field">
            <span>{{ t('orginst.field-client-id') }}</span>
            <input type="text" v-model="requestForm.clientId" required class="cell-mono" placeholder="app_xxxxxxxx" />
            <span class="hint">{{ t('orginst.client-id-hint') }}</span>
          </label>
          <div>
            <p class="scopes-label">{{ t('orginst.field-scopes') }}</p>
            <ul class="scopes">
              <li v-for="scope in state.scopes" :key="scope.name">
                <label class="scope-item">
                  <input type="checkbox" v-model="requestForm.scopes" :value="scope.name" />
                  <span class="scope-name">{{ scope.name }}</span>
                  <span class="scope-desc">{{ scope.description }}</span>
                </label>
              </li>
            </ul>
          </div>
          <button class="btn primary" type="submit" :disabled="busy">{{ t('orginst.request-submit') }}</button>
        </form>
      </template>
    </template>
    <ConfirmDialog
      v-if="revoking"
      :title="t('orginst.revoke')"
      :message="t('orginst.revoke-confirm')"
      :confirm-label="t('orginst.revoke')"
      :busy="busy"
      @confirm="revoke"
      @cancel="revoking = null"
    />
  </main>
</template>
