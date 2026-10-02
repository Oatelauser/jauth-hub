<!-- 通行密钥（B2b）：凭据表 + 注册通行密钥（框架 ceremony：options → navigator.credentials.create
     → /webauthn/register，照 SSR passkey.html 内联 JS，复用/扩展 src/webauthn.js）+ 删除凭据
     （确认 + 框架 DELETE 端点，204 无体）。 -->
<script setup>
import { computed, onMounted, reactive, ref } from 'vue';
import { getJson } from '../api';
import { t } from '../i18n';
import { registrationBody, toArrayBuffer, webauthnDelete, webauthnRawPost } from '../webauthn';
import { fmtDateTime } from '../format';
import ConfirmDialog from '../components/ConfirmDialog.vue';

const state = ref(null);
const error = ref('');
const ceremonyError = ref(false);
const busy = ref(false);
const deleting = ref(null); // 待确认删除的凭据行
const form = reactive({ label: '' });

document.title = t('passkey.title') + ' · jauth-hub';

onMounted(load);

async function load() {
  try {
    state.value = await getJson('/api/selfservice/passkey');
  } catch (e) {
    error.value = e.message;
  }
}

const csrf = computed(() => ({
  csrfToken: state.value ? state.value.csrfToken : null,
  csrfHeaderName: state.value ? state.value.csrfHeaderName : null,
}));

// 注册流程逐行照 SSR passkey.html：选项端点把 challenge/user 存 session，POST /webauthn/register
// 时按 session 配对核验；label 随注册体同行
async function register() {
  ceremonyError.value = false;
  if (!window.PublicKeyCredential) {
    ceremonyError.value = true;
    return;
  }
  if (busy.value) return;
  busy.value = true;
  try {
    const options = await webauthnRawPost('/webauthn/register/options', null, csrf.value);
    const credential = await navigator.credentials.create({
      publicKey: {
        challenge: toArrayBuffer(options.challenge),
        rp: options.rp,
        user: { name: options.user.name, displayName: options.user.displayName, id: toArrayBuffer(options.user.id) },
        pubKeyCredParams: options.pubKeyCredParams,
        timeout: options.timeout,
        excludeCredentials: (options.excludeCredentials || []).map((d) => ({ type: d.type, id: toArrayBuffer(d.id) })),
        authenticatorSelection: options.authenticatorSelection,
        attestation: options.attestation,
      },
    });
    await webauthnRawPost('/webauthn/register', registrationBody(credential, form.label.trim()), csrf.value);
    form.label = '';
    await load();
  } catch {
    ceremonyError.value = true;
  }
  busy.value = false;
}

async function remove() {
  if (!deleting.value || busy.value) return;
  busy.value = true;
  ceremonyError.value = false;
  try {
    await webauthnDelete(deleting.value.credentialId, csrf.value);
    deleting.value = null;
    await load();
  } catch {
    ceremonyError.value = true;
    deleting.value = null;
  }
  busy.value = false;
}
</script>

<template>
  <main class="card card-fluid">
    <h1>{{ t('passkey.title') }}</h1>
    <p class="alert error" v-if="error" role="alert">{{ error }}</p>
    <template v-if="state">
      <div class="alert warn" v-if="!state.passkeyEnabled">{{ t('passkey.unsupported') }}</div>

      <template v-else>
        <div class="alert error" v-if="ceremonyError" role="alert">{{ t('passkey.error') }}</div>

        <h2>{{ t('passkey.create-title') }}</h2>
        <form class="form" @submit.prevent="register">
          <label class="field">
            <span>{{ t('passkey.label') }}</span>
            <input type="text" v-model="form.label" maxlength="100" required :placeholder="t('passkey.label-placeholder')" />
          </label>
          <button class="btn primary" type="submit" :disabled="busy">{{ t('passkey.create-submit') }}</button>
        </form>

        <h2 class="section-gap">{{ t('passkey.list-title') }}</h2>
        <div class="empty" v-if="!state.credentials.length"><span class="empty-title">{{ t('passkey.empty') }}</span></div>
        <table class="data-table" v-else>
          <thead>
            <tr>
              <th>{{ t('passkey.col-label') }}</th>
              <th>{{ t('passkey.col-credential-id') }}</th>
              <th>{{ t('passkey.col-created') }}</th>
              <th>{{ t('passkey.col-last-used') }}</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="credential in state.credentials" :key="credential.credentialId">
              <td>{{ credential.label === null ? t('passkey.unnamed') : credential.label }}</td>
              <td class="cell-mono">{{ credential.credentialIdShort }}</td>
              <td>{{ fmtDateTime(credential.createdAt) }}</td>
              <td>{{ fmtDateTime(credential.lastUsedAt) }}</td>
              <td>
                <button class="btn secondary" type="button" @click="deleting = credential">{{ t('passkey.delete') }}</button>
              </td>
            </tr>
          </tbody>
        </table>
      </template>
    </template>
    <ConfirmDialog
      v-if="deleting"
      :title="t('passkey.delete')"
      :message="t('passkey.delete-confirm')"
      :confirm-label="t('passkey.delete')"
      :busy="busy"
      @confirm="remove"
      @cancel="deleting = null"
    />
  </main>
</template>
