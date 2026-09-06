import { VueQueryPlugin } from '@tanstack/vue-query';
import { createPinia } from 'pinia';
import { createApp } from 'vue';

import App from './vue/App.vue';
import { router } from './vue/router';
import './styles.css';
import './prototype.css';
import './align-prototype.css';
// Vue shell corrections intentionally load after the legacy prototype styles so
// responsive and accessibility rules remain authoritative at runtime.
import './vue/vue-shell.css';

const root = document.getElementById('root');
if (!root) throw new Error('haoemr root element is missing');

const app = createApp(App);
app.use(createPinia());
app.use(VueQueryPlugin);
app.use(router);

void router.isReady().then(() => app.mount(root));
