import { createRouter, createWebHistory } from 'vue-router';
import { h } from 'vue';
const Placeholder = name => ({ render: () => h('section', [h('h2', name), h('p', 'Feature page placeholder')]) });
const routes = [
 {path:'/login',component:Placeholder('Login'),meta:{public:true}}, {path:'/register',component:Placeholder('Register'),meta:{public:true}},
 {path:'/resources',component:Placeholder('Resources'),meta:{student:true}}, {path:'/resources/:id',component:Placeholder('Resource detail'),meta:{student:true}},
 {path:'/bookings',component:Placeholder('Bookings'),meta:{student:true}}, {path:'/bookings/:id',component:Placeholder('Booking detail'),meta:{student:true}},
 {path:'/admin/categories',component:Placeholder('Admin categories'),meta:{admin:true}}, {path:'/admin/resources',component:Placeholder('Admin resources'),meta:{admin:true}},
 {path:'/admin/rules',component:Placeholder('Admin rules'),meta:{admin:true}}, {path:'/admin/closures',component:Placeholder('Admin closures'),meta:{admin:true}},
 {path:'/admin/approvals',component:Placeholder('Admin approvals'),meta:{admin:true}}, {path:'/admin/users',component:Placeholder('Admin users'),meta:{admin:true}}
];
export default createRouter({history:createWebHistory(),routes});
