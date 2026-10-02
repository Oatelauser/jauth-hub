<!-- /demo 教学区自有布局（B4，壳外）：顶部流程图步骤条高亮当前步 + httplog 侧栏 + "发生了什么"
     折叠说明块——三层教学在 Vue 里全量保留（SPEC §7 v1.5 口径）。视觉保留暗色渐变基因
     （core jauth.css 的 --bg-gradient 一系），与主站企业级浅皮刻意区分；全部选择器挂
     .demo-zone 之下，主站皮肤零感染。educational=false 时三层整块退场（照 SSR th:if 语义）。 -->
<script setup>
import { t } from '../i18n';

defineProps({
  step: { type: Number, required: true },
  log: { type: Object, required: true },
  title: { type: String, required: true },
  teach: { type: String, required: true },
  educational: { type: Boolean, default: true },
});

const FLOW_STEPS = [1, 2, 3, 4];
</script>

<template>
  <div class="demo-zone">
    <nav class="demo-steps" v-if="educational" :aria-label="t('demo.flow-title')">
      <ol>
        <li v-for="n in FLOW_STEPS" :key="n" :class="{ active: n === step }">
          {{ t('demo.flow.step' + n) }}
        </li>
      </ol>
    </nav>
    <div class="demo-columns">
      <aside class="demo-side" v-if="educational">
        <section class="demo-panel">
          <h2>{{ t('demo.httplog') }}</h2>
          <div class="demo-log-entries" aria-live="polite">
            <p class="demo-log-empty" v-if="!log.entries.length">{{ t('demo.httplog-empty') }}</p>
            <div v-for="entry in log.entries" :key="entry.id" class="demo-log-entry" :class="entry.level">
              <span class="demo-log-time">{{ entry.time }}</span>
              <span class="demo-log-text" v-if="entry.text">{{ entry.text }}</span>
              <template v-else>
                <span class="demo-log-text">{{ entry.method }} {{ entry.url }}</span>
                <span class="demo-log-text" v-if="entry.status !== null">
                  {{ entry.status }} · {{ entry.elapsed }}ms · {{ entry.summary }}
                </span>
                <details class="demo-log-body" v-if="entry.body">
                  <summary>{{ t('demo.log-body') }}</summary>
                  <pre>{{ entry.body }}</pre>
                </details>
              </template>
            </div>
          </div>
        </section>
      </aside>
      <main class="card demo-card">
        <p class="brand">{{ t('brand') }}</p>
        <h1>{{ title }}</h1>
        <details class="demo-teach" v-if="educational">
          <summary>{{ t('demo.what-happened') }}</summary>
          <p>{{ teach }}</p>
        </details>
        <slot />
      </main>
    </div>
  </div>
</template>
