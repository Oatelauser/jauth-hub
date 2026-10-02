<!-- PAT 管理页（B2b）：创建表单（scope 勾选 + 有效期阶梯 validity-{days} 前端字典）+ 令牌表。
     创建成功 token 仅此一次展示（明示不可再查）；吊销必经确认对话框。 -->
<script setup>
import { computed, onMounted, reactive, ref } from 'vue';
import { getJson, postJson } from '../api';
import { fmtDateTime } from '../format';
import { t } from '../i18n';
import ConfirmDialog from '../components/ConfirmDialog.vue';

const state = ref(null);
const error = ref('');
const token = ref(''); // 明文令牌唯一出现位：刷新即失
const revoking = ref(null);
const busy = ref(false);
const form = reactive({ name: '', scopes: [], validityDays: null });

document.title = t('pat.title') + ' · jauth-hub';

onMounted(load);

async function load() {
  try {
    state.value = await getJson('/api/selfservice/pat');
    if (form.validityDays === null) form.validityDays = state.value.defaultValidityDays;
  } catch (e) {
    error.value = e.message;
  }
}

const csrf = computed(() => ({
  csrfToken: state.value ? state.value.csrfToken : null,
  csrfHeaderName: state.value ? state.value.csrfHeaderName : null,
}));

async function create() {
  if (busy.value) return;
  busy.value = true;
  error.value = '';
  try {
    const data = await postJson(
      '/selfservice/pat',
      { name: form.name.trim(), scopes: form.scopes, validityDays: Number(form.validityDays) },
      csrf.value
    );
    token.value = data.token;
    form.name = '';
    form.scopes = [];
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
    await postJson('/selfservice/pat/' + encodeURIComponent(id) + '/revoke', undefined, csrf.value);
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
    <h1>{{ t('pat.title') }}</h1>
    <p class="alert error" v-if="error" role="alert">{{ error }}</p>
    <template v-if="state">
      <div class="alert warn" v-if="!state.patSupported">{{ t('pat.unsupported') }}</div>

      <template v-else>
        <!-- shown-once：明文令牌只显示这一次，离开本页后无法再次查看 -->
        <div class="alert warn" v-if="token" role="alert">
          {{ t('pat.token-once') }}
          <code class="code-line">{{ token }}</code>
        </div>

        <h2>{{ t('pat.create-title') }}</h2>
        <form class="form" @submit.prevent="create">
          <label class="field">
            <span>{{ t('pat.name') }}</span>
            <input type="text" v-model="form.name" maxlength="100" required :placeholder="t('pat.name-placeholder')" />
          </label>
          <div>
            <p class="scopes-label">{{ t('pat.scopes') }}</p>
            <ul class="scopes">
              <li v-for="scope in state.scopes" :key="scope.name">
                <label class="scope-item">
                  <input type="checkbox" v-model="form.scopes" :value="scope.name" />
                  <span class="scope-name">{{ scope.name }}</span>
                  <span class="scope-desc">{{ scope.description }}</span>
                </label>
              </li>
            </ul>
          </div>
          <label class="field">
            <span>{{ t('pat.validity') }}</span>
            <select v-model="form.validityDays" required>
              <option v-for="days in state.validityDays" :key="days" :value="days">{{ t('pat.validity-' + days) }}</option>
            </select>
          </label>
          <button class="btn primary" type="submit" :disabled="busy">{{ t('pat.create-submit') }}</button>
        </form>

        <h2 class="section-gap">{{ t('pat.list-title') }}</h2>
        <div class="empty" v-if="!state.pats.length"><span class="empty-title">{{ t('pat.empty') }}</span></div>
        <table class="data-table" v-else>
          <thead>
            <tr>
              <th>{{ t('pat.col-name') }}</th>
              <th>{{ t('pat.col-prefix') }}</th>
              <th>{{ t('pat.col-scopes') }}</th>
              <th>{{ t('pat.col-created') }}</th>
              <th>{{ t('pat.col-expires') }}</th>
              <th>{{ t('pat.col-last-used') }}</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="pat in state.pats" :key="pat.id">
              <!-- 存量行（V7 前）name 为 null：回退未命名，不展示空白单元格 -->
              <td class="cell-mono">{{ pat.name === null ? t('pat.unnamed') : pat.name }}</td>
              <td class="cell-mono">{{ pat.prefix }}</td>
              <td>{{ pat.scopes.join(' ') }}</td>
              <td>{{ fmtDateTime(pat.createdAt) }}</td>
              <td>
                {{ fmtDateTime(pat.expiresAt) }}
                <span class="pill pill-danger" v-if="pat.expired">{{ t('pat.expired') }}</span>
              </td>
              <td>{{ fmtDateTime(pat.lastUsedAt) }}</td>
              <td>
                <button class="btn secondary" type="button" @click="revoking = pat">{{ t('pat.revoke') }}</button>
              </td>
            </tr>
          </tbody>
        </table>
      </template>
    </template>
    <ConfirmDialog
      v-if="revoking"
      :title="t('pat.revoke')"
      :message="t('pat.revoke-confirm')"
      :confirm-label="t('pat.revoke')"
      :busy="busy"
      @confirm="revoke"
      @cancel="revoking = null"
    />
  </main>
</template>
