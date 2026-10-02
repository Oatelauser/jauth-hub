<!-- 组织应用（B2b，OWNER 面）：org 应用列表 + 注册表单（照 SSR 页内联式）+ 编辑对话框 + 轮转
     secret（shown-once）+ 删除确认。OWNER 门失败（A0508）渲染为页面错误态，不白屏。 -->
<script setup>
import { computed, onMounted, reactive, ref } from 'vue';
import { RouterLink } from 'vue-router';
import { getJson, postJson } from '../api';
import { deleteJson } from '../rest';
import { fmtDateTime } from '../format';
import { t } from '../i18n';
import ConfirmDialog from '../components/ConfirmDialog.vue';

// orgId 从 SPA 路径自取（router.js 注：页参数不入路由状态，照 consent 页 location 先例）
const orgId = (window.location.pathname.split('/')[4] || '');

const state = ref(null);
const error = ref('');
const busy = ref(false);
const editing = ref(null);
const confirming = ref(null); // { kind: 'rotate'|'delete', app }
const secretBanner = ref(null); // shown-once：注册/轮转回显 clientId + 明文 secret

const registerForm = reactive({ name: '', redirectUris: '', confidential: 'public' });
const editForm = reactive({ name: '', redirectUris: '' });

document.title = t('orgapps.title') + ' · jauth-hub';

onMounted(load);

async function load() {
  try {
    state.value = await getJson('/api/selfservice/orgs/' + encodeURIComponent(orgId) + '/apps');
  } catch (e) {
    error.value = e.message; // A0508 非 OWNER / B0502 org 不存在：页面错误态
  }
}

const csrf = computed(() => ({
  csrfToken: state.value ? state.value.csrfToken : null,
  csrfHeaderName: state.value ? state.value.csrfHeaderName : null,
}));

async function register() {
  if (busy.value) return;
  busy.value = true;
  error.value = '';
  try {
    const data = await postJson(
      '/selfservice/orgs/' + encodeURIComponent(orgId) + '/apps',
      {
        name: registerForm.name.trim(),
        redirectUris: registerForm.redirectUris,
        confidential: registerForm.confidential === 'confidential',
      },
      csrf.value
    );
    secretBanner.value = data;
    registerForm.name = '';
    registerForm.redirectUris = '';
    registerForm.confidential = 'public';
    await load();
  } catch (e) {
    error.value = e.message;
  }
  busy.value = false;
}

function openEdit(app) {
  editing.value = app;
  editForm.name = app.name;
  editForm.redirectUris = app.redirectUris.join('\n');
}

async function saveEdit() {
  if (!editing.value || busy.value) return;
  busy.value = true;
  error.value = '';
  try {
    await postJson(
      '/selfservice/orgs/' + encodeURIComponent(orgId) + '/apps/' + encodeURIComponent(editing.value.id),
      { name: editForm.name.trim(), redirectUris: editForm.redirectUris },
      csrf.value
    );
    editing.value = null;
    await load();
  } catch (e) {
    error.value = e.message;
  }
  busy.value = false;
}

async function runConfirmed() {
  const action = confirming.value;
  if (!action || busy.value) return;
  busy.value = true;
  error.value = '';
  const base = '/selfservice/orgs/' + encodeURIComponent(orgId) + '/apps/' + encodeURIComponent(action.app.id);
  try {
    if (action.kind === 'rotate') {
      secretBanner.value = await postJson(base + '/secret', undefined, csrf.value);
    } else {
      await deleteJson(base, csrf.value);
    }
    confirming.value = null;
    await load();
  } catch (e) {
    error.value = e.message;
    confirming.value = null;
  }
  busy.value = false;
}
</script>

<template>
  <main class="card card-fluid">
    <h1>
      {{ t('orgapps.title') }}
      <span class="pill pill-accent" v-if="state && state.org">{{ state.org.orgName }}</span>
    </h1>
    <p class="hint"><RouterLink to="/selfservice/my-orgs">{{ t('common.back-my-orgs') }}</RouterLink></p>
    <p class="alert error" v-if="error" role="alert">{{ error }}</p>
    <template v-if="state">
      <div class="alert warn" v-if="!state.orgAppsSupported">{{ t('orgapps.unsupported') }}</div>

      <template v-else>
        <!-- shown-once：机密应用的 client_secret 只显示这一次 -->
        <div class="alert warn" v-if="secretBanner" role="alert">
          <template v-if="secretBanner.clientSecret">{{ t('orgapps.secret-once') }}</template>
          <template v-else>{{ t('orgapps.created-banner') }}</template>
          {{ t('orgapps.col-client-id') }}：<code>{{ secretBanner.clientId }}</code>
          <code class="code-line" v-if="secretBanner.clientSecret">{{ secretBanner.clientSecret }}</code>
        </div>

        <h2>{{ t('orgapps.list-title') }}</h2>
        <div class="empty" v-if="!state.apps.length"><span class="empty-title">{{ t('orgapps.empty') }}</span></div>
        <table class="data-table" v-else>
          <thead>
            <tr>
              <th>{{ t('orgapps.col-name') }}</th>
              <th>{{ t('orgapps.col-client-id') }}</th>
              <th>{{ t('orgapps.col-type') }}</th>
              <th>{{ t('orgapps.col-redirects') }}</th>
              <th>{{ t('orgapps.col-created') }}</th>
              <th>{{ t('orgapps.col-actions') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="app in state.apps" :key="app.id">
              <td>{{ app.name }}</td>
              <td class="cell-mono">{{ app.clientId }}</td>
              <td>
                <span class="pill" :class="app.confidential ? 'pill-accent' : 'pill-muted'">
                  {{ app.confidential ? t('orgapps.type-confidential') : t('orgapps.type-public') }}
                </span>
              </td>
              <td class="cell-mono">{{ app.redirectUris.join(' ') }}</td>
              <td>{{ fmtDateTime(app.createdAt) }}</td>
              <td>
                <div class="table-actions">
                  <button class="btn secondary" type="button" v-if="app.confidential" @click="confirming = { kind: 'rotate', app }">
                    {{ t('orgapps.rotate') }}
                  </button>
                  <button class="btn secondary" type="button" @click="openEdit(app)">{{ t('orgapps.edit') }}</button>
                  <button class="btn secondary" type="button" @click="confirming = { kind: 'delete', app }">{{ t('orgapps.delete') }}</button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>

        <h2 class="section-gap">{{ t('orgapps.register-title') }}</h2>
        <form class="form" @submit.prevent="register">
          <label class="field">
            <span>{{ t('orgapps.field-name') }}</span>
            <input type="text" v-model="registerForm.name" maxlength="100" required />
          </label>
          <label class="field">
            <span>{{ t('orgapps.field-redirects') }}</span>
            <textarea v-model="registerForm.redirectUris" required placeholder="https://app.example.com/callback"></textarea>
            <span class="hint">{{ t('orgapps.redirects-hint') }}</span>
          </label>
          <label class="field">
            <span>{{ t('orgapps.field-type') }}</span>
            <select v-model="registerForm.confidential">
              <option value="public">{{ t('orgapps.type-public-desc') }}</option>
              <option value="confidential">{{ t('orgapps.type-confidential-desc') }}</option>
            </select>
          </label>
          <button class="btn primary" type="submit" :disabled="busy">{{ t('orgapps.create-submit') }}</button>
        </form>
      </template>
    </template>

    <div class="dialog-backdrop" v-if="editing" @click.self="editing = null">
      <div class="dialog" role="dialog" aria-modal="true" :aria-label="t('orgapps.edit-title')">
        <h2>{{ t('orgapps.edit-title') }}</h2>
        <form class="form" @submit.prevent="saveEdit">
          <label class="field">
            <span>{{ t('orgapps.field-name') }}</span>
            <input type="text" v-model="editForm.name" maxlength="100" required />
          </label>
          <label class="field">
            <span>{{ t('orgapps.field-redirects') }}</span>
            <textarea v-model="editForm.redirectUris" required></textarea>
            <span class="hint">{{ t('orgapps.redirects-hint') }}</span>
          </label>
          <div class="actions">
            <button class="btn secondary" type="button" @click="editing = null">{{ t('common.cancel') }}</button>
            <button class="btn primary" type="submit" :disabled="busy">{{ t('orgapps.edit') }}</button>
          </div>
        </form>
      </div>
    </div>

    <ConfirmDialog
      v-if="confirming"
      :title="confirming.kind === 'rotate' ? t('orgapps.rotate') : t('orgapps.delete')"
      :message="confirming.kind === 'rotate' ? t('orgapps.rotate-confirm') : t('orgapps.delete-confirm')"
      :confirm-label="confirming.kind === 'rotate' ? t('orgapps.rotate') : t('orgapps.delete')"
      :busy="busy"
      @confirm="runConfirmed"
      @cancel="confirming = null"
    />
  </main>
</template>
