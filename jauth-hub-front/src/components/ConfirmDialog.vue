<!-- 危险操作确认对话框（B2b 约束:删除/轮转/撤销/吊销/移除类动作必经确认）:复用 B2a 的
     dialog-backdrop/dialog 组件类;confirm 按钮恒 danger 型,标题/文案/按钮字由调用页给 i18n。 -->
<script setup>
import { t } from '../i18n';

defineProps({
  title: { type: String, required: true },
  message: { type: String, required: true },
  confirmLabel: { type: String, required: true },
  busy: { type: Boolean, default: false },
});
const emit = defineEmits(['confirm', 'cancel']);
</script>

<template>
  <div class="dialog-backdrop" @click.self="emit('cancel')">
    <div class="dialog" role="dialog" aria-modal="true" :aria-label="title">
      <h2>{{ title }}</h2>
      <p>{{ message }}</p>
      <div class="actions">
        <button class="btn secondary" type="button" @click="emit('cancel')">{{ t('common.cancel') }}</button>
        <button class="btn danger" type="button" :disabled="busy" @click="emit('confirm')">{{ confirmLabel }}</button>
      </div>
    </div>
  </div>
</template>
