import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';

// base '/front/'：B4 装配时后端以 /front/** 直接服务本工程 dist（SPEC §1 混合用法，Vue 随制品打包零外链）
export default defineConfig({
  base: '/front/',
  plugins: [vue()],
  test: {
    environment: 'jsdom',
  },
});
