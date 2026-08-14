<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore, safeRedirect } from '../../stores/auth'

const store = useAuthStore()
const router = useRouter()
const route = useRoute()
const formRef = ref()
const loading = ref(false)
const error = ref('')
const notice = ref(route.query.registered ? '注册成功，请登录' : '')
const form = reactive({ username: '', password: '' })
const usernameRule = /^[A-Za-z0-9_]{3,50}$/
const passwordValid = (value) => { const bytes = new TextEncoder().encode(value).length; return bytes >= 8 && bytes <= 72 }
const rules = {
  username: [{ validator: (_r, v, done) => done(usernameRule.test(v.trim()) ? undefined : new Error('请输入 3-50 位字母、数字或下划线账号')), trigger: 'blur' }],
  password: [{ validator: (_r, v, done) => done(passwordValid(v) ? undefined : new Error('密码长度须为 8-72 字节')), trigger: 'blur' }],
}
async function submit() {
  if (loading.value) return
  error.value = ''
  try { await formRef.value.validate() } catch { return }
  loading.value = true
  try {
    await store.login({ username: form.username.trim(), password: form.password })
    const requested = safeRedirect(route.query.redirect)
    const destination = route.query.redirect ? requested : (store.role === 'ADMIN' ? '/admin/resources' : '/resources')
    router.replace(destination)
  } catch { error.value = '账号或密码错误' } finally { loading.value = false }
}
</script>

<template>
  <main class="auth">
    <h1>登录</h1>
    <el-alert v-if="notice" :title="notice" type="success" :closable="false" show-icon />
    <el-alert v-if="error" id="login-error" :title="error" type="error" :closable="false" show-icon role="alert" />
    <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="submit">
      <el-form-item label="账号" prop="username">
        <el-input id="login-username" v-model="form.username" autocomplete="username" aria-describedby="login-error" :aria-invalid="!!error" />
      </el-form-item>
      <el-form-item label="密码" prop="password">
        <el-input id="login-password" v-model="form.password" type="password" show-password autocomplete="current-password" aria-describedby="login-error" :aria-invalid="!!error" />
      </el-form-item>
      <el-button type="primary" native-type="submit" :loading="loading">{{ loading ? '登录中…' : '登录' }}</el-button>
    </el-form>
    <RouterLink to="/register">注册账号</RouterLink>
  </main>
</template>
