<script setup>
import { computed, onMounted, reactive, ref } from 'vue';
import { getJson, postJson } from '../api';
import { flowSteps, t } from '../i18n';

const steps = flowSteps('authcode');
const state = ref(null);
const error = ref('');
const submitting = ref(false);
const passkeyError = ref(false);
const passkeyMessage = ref('');
const form = reactive({ username: '', password: '' });
const csrf = computed(() => ({
  csrfToken: state.value ? state.value.csrfToken : null,
  csrfHeaderName: state.value ? state.value.csrfHeaderName : null,
}));

document.title = t('login.title') + ' · jauth-hub';

onMounted(async () => {
  // 自身 query 原样转发：?error 反射与 SSR ${param.error} 同语义
  try {
    state.value = await getJson('/api/login' + window.location.search);
    if (state.value.error) error.value = t('login.error');
  } catch (e) {
    error.value = e.message;
  }
});

async function submit() {
  if (submitting.value) return;
  submitting.value = true;
  error.value = '';
  try {
    const data = await postJson('/api/login', { username: form.username, password: form.password }, csrf.value);
    // 登录成功即 CSRF token 换发（B2 现场事实）：此页不再发任何请求，直接整页导航离开
    window.location.href = data.redirectUrl;
  } catch (e) {
    error.value = e.message; // A0520/A0521 的 message 即 SSR ?error 同款文案
    submitting.value = false;
  }
}

// passkey 入口：流程逐行照 SSR login.html 内联 JS（options→credentials.get→/login/webauthn）
async function passkeyLogin() {
  passkeyError.value = false;
  passkeyMessage.value = '';
  if (!window.PublicKeyCredential) {
    passkeyMessage.value = t('login.passkey.unsupported');
    passkeyError.value = true;
    return;
  }
  try {
    // 每次尝试都取新 options：challenge 存 session，断言按 session 配对
    const options = await rawPost('/webauthn/authenticate/options');
    const credential = await navigator.credentials.get({
      publicKey: {
        challenge: toArrayBuffer(options.challenge),
        rpId: options.rpId,
        timeout: options.timeout,
        userVerification: options.userVerification,
        allowCredentials: (options.allowCredentials || []).map((d) => ({ type: d.type, id: toArrayBuffer(d.id) })),
      },
    });
    const body = await rawPost('/login/webauthn', assertionBody(credential));
    window.location.href = body.redirectUrl || '/';
  } catch {
    passkeyError.value = true;
  }
}

// /webauthn/* 与 /login/webauthn 是框架原生端点，裸 JSON（非 ResponseRenderer），不走 api.js 包装
async function rawPost(path, body) {
  const headers = { 'Content-Type': 'application/json' };
  if (csrf.value.csrfToken && csrf.value.csrfHeaderName) headers[csrf.value.csrfHeaderName] = csrf.value.csrfToken;
  const response = await fetch(path, {
    method: 'POST',
    headers,
    credentials: 'same-origin',
    body: JSON.stringify(body || {}),
  });
  if (!response.ok) throw new Error(path + ' ' + response.status);
  return response.json();
}

function assertionBody(credential) {
  return {
    id: credential.id,
    rawId: toBase64Url(credential.rawId),
    type: credential.type,
    response: {
      clientDataJSON: toBase64Url(credential.response.clientDataJSON),
      authenticatorData: toBase64Url(credential.response.authenticatorData),
      signature: toBase64Url(credential.response.signature),
      userHandle: credential.response.userHandle ? toBase64Url(credential.response.userHandle) : null,
    },
    clientExtensionResults: {},
    authenticatorAttachment: credential.authenticatorAttachment,
  };
}

// base64url（无 padding，对齐 SS7 Bytes 序列化）↔ ArrayBuffer：WebAuthn 二进制字段的 JSON 线格式
function toArrayBuffer(value) {
  const binary = window.atob(value.replace(/-/g, '+').replace(/_/g, '/'));
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes.buffer;
}

function toBase64Url(buffer) {
  const bytes = new Uint8Array(buffer);
  let binary = '';
  for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
  return window.btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}
</script>

<template>
  <div class="page">
    <aside class="sidebar" v-if="state && state.educational">
      <section class="panel">
        <h2>{{ t('teach.flowTitle') }}</h2>
        <nav class="steps" aria-label="flow">
          <ol>
            <li v-for="(step, i) in steps" :key="i" :class="{ active: i === 0 }">{{ step }}</li>
          </ol>
        </nav>
      </section>
      <section class="panel httplog">
        <h2>{{ t('teach.httplogTitle') }}</h2>
        <p class="placeholder">{{ t('teach.httplogPlaceholder') }}</p>
      </section>
    </aside>
    <main class="card">
      <p class="brand">{{ t('brand') }}</p>
      <h1>{{ t('login.title') }}</h1>
      <p class="alert" v-if="error">{{ error }}</p>
      <details class="teach" v-if="state && state.educational">
        <summary>{{ t('teach.whatHappened') }}</summary>
        <p>{{ t('login.teach') }}</p>
        <p v-if="state.passkeyEnabled">{{ t('login.passkey.teach') }}</p>
      </details>
      <form class="form" @submit.prevent="submit">
        <label class="field">
          <span>{{ t('login.username') }}</span>
          <input type="text" v-model="form.username" autocomplete="username" required />
        </label>
        <label class="field">
          <span>{{ t('login.password') }}</span>
          <input type="password" v-model="form.password" autocomplete="current-password" required />
        </label>
        <button class="btn primary" type="submit" :disabled="submitting">{{ t('login.submit') }}</button>
      </form>
      <template v-if="state && state.passkeyEnabled">
        <hr class="pk-divider" />
        <button class="btn" type="button" @click="passkeyLogin">{{ t('login.passkey.button') }}</button>
        <p class="alert" v-if="passkeyError" role="alert">
          {{ passkeyMessage || t('login.passkey.error') }}
        </p>
      </template>
    </main>
  </div>
</template>
