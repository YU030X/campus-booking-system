<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../../stores/auth'

const store = useAuthStore(); const router = useRouter(); const formRef = ref(); const loading = ref(false); const error = ref('')
const form = reactive({ username: '', password: '', confirmPassword: '', realName: '', studentNo: '', phone: '', email: '' })
const usernameRegex = /^[A-Za-z0-9_]{3,50}$/; const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/; const bytes = (v) => new TextEncoder().encode(v).length
const rules = {
  username: [{ validator: (_r, v, d) => d(usernameRegex.test(v.trim()) ? undefined : new Error('请输入 3-50 位字母、数字或下划线账号')), trigger: 'blur' }],
  password: [{ validator: (_r, v, d) => d(bytes(v) >= 8 && bytes(v) <= 72 ? undefined : new Error('密码长度须为 8-72 字节')), trigger: 'blur' }],
  confirmPassword: [{ validator: (_r, v, d) => d(v === form.password ? undefined : new Error('两次密码不一致')), trigger: 'blur' }],
  realName: [{ validator: (_r, v, d) => d(v.trim().length >= 1 && v.trim().length <= 50 ? undefined : new Error('姓名长度须为 1-50 个字符')), trigger: 'blur' }],
  studentNo: [{ validator: (_r, v, d) => d(!v.trim() || v.trim().length <= 30 ? undefined : new Error('学号不能超过 30 个字符')), trigger: 'blur' }],
  phone: [{ validator: (_r, v, d) => d(!v.trim() || /^1[3-9]\d{9}$/.test(v.trim()) ? undefined : new Error('请输入有效手机号')), trigger: 'blur' }],
  email: [{ validator: (_r, v, d) => d(!v.trim() || (v.trim().length <= 100 && emailRegex.test(v.trim())) ? undefined : new Error('请输入有效邮箱')), trigger: 'blur' }],
}
async function submit() {
  if (loading.value) return; error.value = ''
  try { await formRef.value.validate() } catch { return }
  loading.value = true
  const payload = { username: form.username.trim(), password: form.password, realName: form.realName.trim(), studentNo: form.studentNo.trim() || null, phone: form.phone.trim() || null, email: form.email.trim() || null }
  try { await store.register(payload); router.replace({ path: '/login', query: { registered: '1' } }) } catch (e) { const code = e?.response?.data?.code; const status = e?.response?.status; error.value = status === 409 || code === 40900 || code === '40900' || code === 41000 || code === '41000' ? '账号或学号已存在' : '注册失败，请稍后重试' } finally { loading.value = false }
}
</script>

<template>
  <main class="auth"><h1>注册</h1>
    <el-alert v-if="error" id="register-error" :title="error" type="error" :closable="false" show-icon role="alert" />
    <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="submit">
      <el-form-item label="账号" prop="username"><el-input id="register-username" v-model="form.username" autocomplete="username" aria-describedby="register-error" :aria-invalid="!!error" /></el-form-item>
      <el-form-item label="密码" prop="password"><el-input id="register-password" v-model="form.password" type="password" show-password autocomplete="new-password" aria-describedby="register-error" :aria-invalid="!!error" /></el-form-item>
      <el-form-item label="确认密码" prop="confirmPassword"><el-input id="register-confirm-password" v-model="form.confirmPassword" type="password" show-password autocomplete="new-password" aria-describedby="register-error" :aria-invalid="!!error" /></el-form-item>
      <el-form-item label="姓名" prop="realName"><el-input id="register-real-name" v-model="form.realName" /></el-form-item>
      <el-form-item label="学号" prop="studentNo"><el-input id="register-student-no" v-model="form.studentNo" /></el-form-item>
      <el-form-item label="手机号" prop="phone"><el-input id="register-phone" v-model="form.phone" autocomplete="tel" /></el-form-item>
      <el-form-item label="邮箱" prop="email"><el-input id="register-email" v-model="form.email" type="email" autocomplete="email" /></el-form-item>
      <el-button type="primary" native-type="submit" :loading="loading">{{ loading ? '提交中…' : '注册' }}</el-button>
    </el-form>
    <RouterLink to="/login">返回登录</RouterLink>
  </main>
</template>
