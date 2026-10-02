<!-- 我的组织（B2b）：组织表（orgId/orgName/role）+ 创建表单；OWNER 行链到三个 org 子页
     （apps/installations/members，窜连主干）；MEMBER 行不渲染入口（越权由服务层 A0508 兜底）。 -->
<script setup>
import { computed, onMounted, reactive, ref } from 'vue';
import { RouterLink } from 'vue-router';
import { getJson, postJson } from '../api';
import { t } from '../i18n';

const state = ref(null);
const error = ref('');
const busy = ref(false);
const form = reactive({ name: '' });

document.title = t('myorgs.title') + ' · jauth-hub';

onMounted(load);

async function load() {
  try {
    state.value = await getJson('/api/selfservice/my-orgs');
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
    await postJson('/selfservice/my-orgs', { name: form.name.trim() }, csrf.value);
    form.name = '';
    await load();
  } catch (e) {
    error.value = e.message;
  }
  busy.value = false;
}
</script>

<template>
  <main class="card card-fluid">
    <h1>{{ t('myorgs.title') }}</h1>
    <p class="alert error" v-if="error" role="alert">{{ error }}</p>
    <template v-if="state">
      <div class="alert warn" v-if="!state.orgsSupported">{{ t('myorgs.unsupported') }}</div>

      <template v-else>
        <form class="form" @submit.prevent="create">
          <label class="field">
            <span>{{ t('myorgs.field-name') }}</span>
            <input type="text" v-model="form.name" maxlength="50" required :placeholder="t('myorgs.name-placeholder')" />
          </label>
          <button class="btn primary" type="submit" :disabled="busy">{{ t('myorgs.create-submit') }}</button>
        </form>

        <h2 class="section-gap">{{ t('myorgs.list-title') }}</h2>
        <div class="empty" v-if="!state.orgs.length"><span class="empty-title">{{ t('myorgs.empty') }}</span></div>
        <table class="data-table" v-else>
          <thead>
            <tr>
              <th>{{ t('myorgs.col-name') }}</th>
              <th>{{ t('myorgs.col-role') }}</th>
              <th>{{ t('myorgs.col-actions') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="membership in state.orgs" :key="membership.orgId">
              <td>{{ membership.orgName }}</td>
              <td>
                <span class="pill" :class="membership.role === 'OWNER' ? 'pill-success' : 'pill-muted'">
                  {{ membership.role === 'OWNER' ? t('myorgs.role-owner') : t('myorgs.role-member') }}
                </span>
              </td>
              <td>
                <div class="table-actions" v-if="membership.role === 'OWNER'">
                  <RouterLink :to="'/selfservice/orgs/' + membership.orgId + '/installations'">{{ t('myorgs.link-installations') }}</RouterLink>
                  <RouterLink :to="'/selfservice/orgs/' + membership.orgId + '/apps'">{{ t('myorgs.link-apps') }}</RouterLink>
                  <RouterLink :to="'/selfservice/orgs/' + membership.orgId + '/members'">{{ t('myorgs.link-members') }}</RouterLink>
                </div>
                <span v-else>—</span>
              </td>
            </tr>
          </tbody>
        </table>
      </template>
    </template>
  </main>
</template>
