<script setup>
import { computed, onMounted, ref } from 'vue';
import { getJson } from '../api';
import { t } from '../i18n';

const state = ref(null);
const error = ref('');
const sudoError = ref(false);
const sudoMessage = ref('');

document.title = t('sudo.title') + ' · jauth-hub';

onMounted(async () => {
  const qs = new URLSearchParams(window.location.search);
  const returnTo = qs.get('returnTo');
  try {
    state.value = await getJson('/api/sudo' + (returnTo ? '?returnTo=' + encodeURIComponent(returnTo) : ''));
  } catch (e) {
    error.value = e.message;
  }
});

const csrf = computed(() => ({
  csrfToken: state.value ? state.value.csrfToken : null,
  csrfHeaderName: state.value ? state.value.csrfHeaderName : null,
}));

// passkey 就地升权：流程逐行照 SSR sudo.html 内联 JS（options→credentials.get→/login/webauthn）
async function verify() {
  sudoError.value = false;
  sudoMessage.value = '';
  if (!window.PublicKeyCredential) {
    sudoMessage.value = t('sudo.unsupportedBrowser');
    sudoError.value = true;
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
    // 断言成功即 strong_auth_at 打点；redirectUrl 是框架默认登录后页，无视——回 returnTo（服务端已消毒）
    await rawPost('/login/webauthn', assertionBody(credential));
    window.location.href = state.value.returnTo;
  } catch {
    sudoError.value = true;
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
    <main class="card">
      <p class="brand">{{ t('brand') }}</p>
      <h1>{{ t('sudo.title') }}</h1>
      <p class="alert" v-if="error">{{ error }}</p>
      <template v-if="state">
        <details class="teach" v-if="state.educational">
          <summary>{{ t('teach.whatHappened') }}</summary>
          <p>{{ t('sudo.teach') }}</p>
        </details>

        <div class="alert" v-if="!state.sudoEnabled">{{ t('sudo.unsupported') }}</div>

        <template v-if="state.sudoEnabled">
          <p>{{ t('sudo.intro') }}</p>
          <div class="alert" v-if="sudoError" role="alert">{{ sudoMessage || t('sudo.error') }}</div>
          <button class="btn primary" type="button" @click="verify">{{ t('sudo.verify') }}</button>
          <p class="hint"><a :href="state.returnTo">{{ t('sudo.back') }}</a></p>
        </template>
      </template>
    </main>
  </div>
</template>
