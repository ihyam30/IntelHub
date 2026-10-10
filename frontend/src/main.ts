import { createApp } from 'vue'
import { createPinia } from 'pinia'
import {
  ElButton,
  ElDropdown,
  ElDropdownItem,
  ElDropdownMenu,
  ElDialog,
  ElEmpty,
  ElForm,
  ElFormItem,
  ElInput,
  ElInputNumber,
  ElOption,
  ElPagination,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTag,
} from 'element-plus'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import App from './App.vue'
import router from './router'
import './styles.css'
import './admin-extra.css'
import './redesign.css'

const app = createApp(App)
app.use(createPinia()).use(router)

for (const component of [
  ElButton,
  ElDropdown,
  ElDropdownItem,
  ElDropdownMenu,
  ElDialog,
  ElEmpty,
  ElForm,
  ElFormItem,
  ElInput,
  ElInputNumber,
  ElOption,
  ElPagination,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTag,
]) {
  app.use(component)
}

app.mount('#app')
