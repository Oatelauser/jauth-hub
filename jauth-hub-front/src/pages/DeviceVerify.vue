<script setup>
import { onMounted, ref } from 'vue';
import { getJson } from '../api';
import { flowSteps, t } from '../i18n';

const steps = flowSteps('device');
const state = ref(null);
const error = ref('');

document.title = t('device.title') + ' · jauth-hub';

onMounted(async () => {
  try {
    state.value = await getJson('/api/device/verify');
  } catch (e) {
    error.value = e.message;
  }
});
</script>

<template>
  <div class="page">
    <aside class="sidebar" v-if="state && state.educational">
      <section class="panel">
        <h2>{{ t('teach.flowTitle') }}</h2>
        <nav class="steps" aria-label="flow">
          <ol>
            <li v-for="(step, i) in steps" :key="i" :class="{ active: i === 1 }">{{ step }}</li>
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
      <h1>{{ t('device.title') }}</h1>
      <p class="alert" v-if="error">{{ error }}</p>
      <template v-if="state">
        <p class="hint">{{ t('device.hint') }}</p>
        <details class="teach" v-if="state.educational">
          <summary>{{ t('teach.whatHappened') }}</summary>
          <p>{{ t('device.teach') }}</p>
        </details>
        <!-- 原生表单 POST 浏览器导航，字段照 SSR device-verify.html -->
        <form class="form" action="/device/verify" method="post">
          <input type="hidden" name="_csrf" v-if="state.csrfToken" :value="state.csrfToken" />
          <label class="field">
            <span>{{ t('device.codeLabel') }}</span>
            <input type="text" name="user_code" class="code-input" autocomplete="one-time-code" required />
          </label>
          <button class="btn primary" type="submit">{{ t('device.submit') }}</button>
        </form>
      </template>
    </main>
  </div>
</template>
