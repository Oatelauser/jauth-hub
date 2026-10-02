<script setup>
import { computed, onMounted, ref } from 'vue';
import { getJson } from '../api';
import { t } from '../i18n';
import { assertionBody, toArrayBuffer, webauthnRawPost } from '../webauthn';

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
    const options = await webauthnRawPost('/webauthn/authenticate/options', null, csrf.value);
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
    await webauthnRawPost('/login/webauthn', assertionBody(credential), csrf.value);
    window.location.href = state.value.returnTo;
  } catch {
    sudoError.value = true;
  }
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
