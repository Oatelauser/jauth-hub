import { createApp, h } from 'vue';
import { RouterView } from 'vue-router';
import { router } from './router';
import './assets/app.css';

// 工程形态锁死无 App.vue：根组件即 router-view 出口（render 函数，无需运行时模板编译器）
createApp({ render: () => h(RouterView) }).use(router).mount('#app');
