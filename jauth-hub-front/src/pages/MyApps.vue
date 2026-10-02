<!-- 我的应用（B2b）：应用表 + 注册对话框（my-app-new 复用本页，不独立路由）+ 编辑对话框 +
     轮转 secret（shown-once）+ 删除确认。轮转/删除 sudo 类动作触发 A0515 时由请求包装自动跳验证页。 -->
<script setup>
import { computed, onMounted, reactive, ref } from 'vue';
import { getJson, postJson } from '../api';
import { deleteJson } from '../rest';
import { fmtDateTime } from '../format';
import { t } from '../i18n';
import ConfirmDialog from '../components/ConfirmDialog.vue';

const state = ref(null);
const error = ref('');
const busy = ref(false);
const showRegister = ref(false);
const editing = ref(null); // 待编辑的应用行
const confirming = ref(null); // { kind: 'rotate'|'delete', app } 待确认的危险动作
// shown-once 横幅：注册/轮转回显 clientId + 明文 secret（仅此一次，刷新即失）
const secretBanner = ref(null);

const registerForm = reactive({ name: '', redirectUris: '', confidential: 'public' });
const editForm = reactive({ name: '', redirectUris: '' });

document.title = t('myapps.title') + ' · jauth-hub';

onMounted(load);

async function load() {
  try {
    state.value = await getJson('/api/selfservice/my-apps');
  } catch (e) {
    error.value = e.message;
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
      '/selfservice/my-apps',
      {
        name: registerForm.name.trim(),
        redirectUris: registerForm.redirectUris,
        confidential: registerForm.confidential === 'confidential',
      },
      csrf.value
    );
    secretBanner.value = data; // data.clientSecret 仅机密应用回带
    showRegister.value = false;
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
      '/selfservice/my-apps/' + encodeURIComponent(editing.value.id),
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
  const base = '/selfservice/my-apps/' + encodeURIComponent(action.app.id);
  try {
    if (action.kind === 'rotate') {
      secretBanner.value = await postJson(base + '/secret', undefined, csrf.value); // data.clientSecret 为新明文
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
    <h1>{{ t('myapps.title') }}</h1>
    <p class="alert error" v-if="error" role="alert">{{ error }}</p>
    <template v-if="state">
      <div class="alert warn" v-if="!state.appsSupported">{{ t('myapps.unsupported') }}</div>

      <template v-else>
        <!-- shown-once：机密应用的 client_secret 只显示这一次，离开本页后无法再次查看 -->
        <div class="alert warn" v-if="secretBanner" role="alert">
          <template v-if="secretBanner.clientSecret">{{ t('myapps.secret-once') }}</template>
          <template v-else>{{ t('myapps.created-banner') }}</template>
          {{ t('myapps.col-client-id') }}：<code>{{ secretBanner.clientId }}</code>
          <code class="code-line" v-if="secretBanner.clientSecret">{{ secretBanner.clientSecret }}</code>
        </div>

        <p class="actions">
          <button class="btn primary" type="button" @click="showRegister = true">{{ t('myapps.new-link') }}</button>
        </p>

        <div class="empty" v-if="!state.apps.length"><span class="empty-title">{{ t('myapps.empty') }}</span></div>
        <table class="data-table" v-else>
          <thead>
            <tr>
              <th>{{ t('myapps.col-name') }}</th>
              <th>{{ t('myapps.col-client-id') }}</th>
              <th>{{ t('myapps.col-type') }}</th>
              <th>{{ t('myapps.col-redirects') }}</th>
              <th>{{ t('myapps.col-created') }}</th>
              <th>{{ t('myapps.col-actions') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="app in state.apps" :key="app.id">
              <td>{{ app.name }}</td>
              <td class="cell-mono">{{ app.clientId }}</td>
              <td>
                <span class="pill" :class="app.confidential ? 'pill-accent' : 'pill-muted'">
                  {{ app.confidential ? t('myapps.type-confidential') : t('myapps.type-public') }}
                </span>
              </td>
              <td class="cell-mono">{{ app.redirectUris.join(' ') }}</td>
              <td>{{ fmtDateTime(app.createdAt) }}</td>
              <td>
                <div class="table-actions">
                  <button class="btn secondary" type="button" v-if="app.confidential" @click="confirming = { kind: 'rotate', app }">
                    {{ t('myapps.rotate') }}
                  </button>
                  <button class="btn secondary" type="button" @click="openEdit(app)">{{ t('myapps.edit') }}</button>
                  <button class="btn secondary" type="button" @click="confirming = { kind: 'delete', app }">{{ t('myapps.delete') }}</button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </template>
    </template>

    <!-- 注册对话框（my-app-new 页复用本页状态面与注册端点） -->
    <div class="dialog-backdrop" v-if="showRegister" @click.self="showRegister = false">
      <div class="dialog" role="dialog" aria-modal="true" :aria-label="t('myapps.new-title')">
        <h2>{{ t('myapps.new-title') }}</h2>
        <form class="form" @submit.prevent="register">
          <label class="field">
            <span>{{ t('myapps.field-name') }}</span>
            <input type="text" v-model="registerForm.name" maxlength="100" required />
          </label>
          <label class="field">
            <span>{{ t('myapps.field-redirects') }}</span>
            <textarea v-model="registerForm.redirectUris" required placeholder="https://app.example.com/callback"></textarea>
            <span class="hint">{{ t('myapps.redirects-hint') }}</span>
          </label>
          <label class="field">
            <span>{{ t('myapps.field-type') }}</span>
            <select v-model="registerForm.confidential">
              <option value="public">{{ t('myapps.type-public-desc') }}</option>
              <option value="confidential">{{ t('myapps.type-confidential-desc') }}</option>
            </select>
          </label>
          <div class="actions">
            <button class="btn secondary" type="button" @click="showRegister = false">{{ t('common.cancel') }}</button>
            <button class="btn primary" type="submit" :disabled="busy">{{ t('myapps.create-submit') }}</button>
          </div>
        </form>
      </div>
    </div>

    <!-- 编辑对话框（改名 + redirect 白名单，非销毁性操作不挂 sudo） -->
    <div class="dialog-backdrop" v-if="editing" @click.self="editing = null">
      <div class="dialog" role="dialog" aria-modal="true" :aria-label="t('myapps.edit-title')">
        <h2>{{ t('myapps.edit-title') }}</h2>
        <form class="form" @submit.prevent="saveEdit">
          <label class="field">
            <span>{{ t('myapps.field-name') }}</span>
            <input type="text" v-model="editForm.name" maxlength="100" required />
          </label>
          <label class="field">
            <span>{{ t('myapps.field-redirects') }}</span>
            <textarea v-model="editForm.redirectUris" required></textarea>
            <span class="hint">{{ t('myapps.redirects-hint') }}</span>
          </label>
          <div class="actions">
            <button class="btn secondary" type="button" @click="editing = null">{{ t('common.cancel') }}</button>
            <button class="btn primary" type="submit" :disabled="busy">{{ t('myapps.edit') }}</button>
          </div>
        </form>
      </div>
    </div>

    <ConfirmDialog
      v-if="confirming"
      :title="confirming.kind === 'rotate' ? t('myapps.rotate') : t('myapps.delete')"
      :message="confirming.kind === 'rotate' ? t('myapps.rotate-confirm') : t('myapps.delete-confirm')"
      :confirm-label="confirming.kind === 'rotate' ? t('myapps.rotate') : t('myapps.delete')"
      :busy="busy"
      @confirm="runConfirmed"
      @cancel="confirming = null"
    />
  </main>
</template>
