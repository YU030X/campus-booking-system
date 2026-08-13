import axios from 'axios';
const mode = import.meta.env.VITE_API_MODE || 'real';
if (!['mock','real'].includes(mode)) throw new Error(`Invalid VITE_API_MODE: ${mode}`);
const mockAdapter = async config => ({data:{code:0,message:'success',data:null},status:200,statusText:'OK',headers:{},config,request:null});
export const http = axios.create({ baseURL: mode === 'real' ? '/api/v1' : undefined, adapter: mode === 'mock' ? mockAdapter : undefined });
http.interceptors.request.use(config => { const token = sessionStorage.getItem('token'); if (token) config.headers.Authorization = `Bearer ${token}`; return config; });
export { mode };
