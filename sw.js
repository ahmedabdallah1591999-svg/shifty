'use strict';

const CACHE = 'shift-app-v1';
const ASSETS = ['./', './index.html', './app.js', './manifest.webmanifest', './icon.svg'];

self.addEventListener('install', e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(ASSETS)).then(() => self.skipWaiting()));
});
self.addEventListener('activate', e => { e.waitUntil(self.clients.claim()); });
self.addEventListener('fetch', e => {
  if (e.request.method !== 'GET') return;
  e.respondWith(caches.match(e.request, { ignoreSearch: true }).then(r => r || fetch(e.request)));
});

/* نفس مخزن IndexedDB الذي تكتبه الصفحة */
function idb() {
  return new Promise((res, rej) => {
    const r = indexedDB.open('shift-db', 1);
    r.onupgradeneeded = () => r.result.createObjectStore('state');
    r.onsuccess = () => res(r.result);
    r.onerror = () => rej(r.error);
  });
}
function idbGet(k) {
  return idb().then(db => new Promise((res, rej) => {
    const q = db.transaction('state').objectStore('state').get(k);
    q.onsuccess = () => res(q.result);
    q.onerror = () => rej(q.error);
  }));
}

const AR_D = '٠١٢٣٤٥٦٧٨٩';
const ar = n => String(n).replace(/\d/g, d => AR_D[d]);
const DAY_AR = { sat: 'السبت', sun: 'الأحد', mon: 'الاثنين', tue: 'الثلاثاء', wed: 'الأربعاء', thu: 'الخميس', fri: 'الجمعة' };
const JS_DAY = ['sun', 'mon', 'tue', 'wed', 'thu', 'fri', 'sat'];
function partStart(h) {
  if (h === 12 || h === 1 || h === 2) return 'الظهر';
  if (h >= 3 && h <= 5) return 'العصر';
  return 'الصبح';
}
function partEnd(h) {
  if (h === 12 || h === 1 || h === 2) return 'الظهر';
  if (h === 3 || h === 4) return 'العصر';
  if (h === 5) return 'المغرب';
  return 'بالليل';
}
function cellText(c) {
  if (!c || c === '?') return 'شيفتك مش واضح في الجدول — راجع المدير';
  if (c === 'off') return 'أجازة النهاردة';
  if (c === 'online') return 'شغل أونلاين (من البيت)';
  const m = String(c).match(/^(\d{1,2})-(\d{1,2})$/);
  if (m) return `من ${ar(m[1])} ${partStart(+m[1])} لـ ${ar(m[2])} ${partEnd(+m[2])}`;
  return String(c);
}

async function notifyShift() {
  const roster = await idbGet('roster');
  const me = await idbGet('me');
  if (!roster || !me) return;
  const emp = (roster.employees || []).find(x => x.name === me);
  if (!emp) return;
  const d = new Date();
  const key = JS_DAY[d.getDay()];
  const body = `شيفتك النهاردة ${DAY_AR[key]} ${ar(d.getDate())}/${ar(d.getMonth() + 1)}: ${cellText(emp[key])}`;
  await self.registration.showNotification('شيفتي', {
    body,
    icon: 'icon.svg',
    badge: 'icon.svg',
    dir: 'rtl',
    lang: 'ar',
    tag: 'shift-daily',
    renotify: false
  });
}

self.addEventListener('periodicsync', e => {
  if (e.tag === 'shift-daily') e.waitUntil(notifyShift());
});

self.addEventListener('notificationclick', e => {
  e.notification.close();
  e.waitUntil(
    clients.matchAll({ type: 'window', includeUncontrolled: true }).then(cs =>
      cs.length ? cs[0].focus() : clients.openWindow('./'))
  );
});
