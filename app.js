'use strict';

/* ---------- ثوابت ---------- */
const DAYS = ['sat', 'sun', 'mon', 'tue', 'wed', 'thu', 'fri']; // ترتيب العرض: الأسبوع يبدأ السبت
const DAY_AR = { sat: 'السبت', sun: 'الأحد', mon: 'الاثنين', tue: 'الثلاثاء', wed: 'الأربعاء', thu: 'الخميس', fri: 'الجمعة' };
const JS_DAY = ['sun', 'mon', 'tue', 'wed', 'thu', 'fri', 'sat']; // getDay(): 0=الأحد
const QUICK = ['9-5', '10-2', '11-3', '11-2', 'online', 'off', '?'];

let roster = null;   // {name, week, employees:[{name, sat..fri}]}
let me = null;       // اسم الموظف المختار
let tab = 'home';
let editMode = false;
let deferredInstall = null;

/* ---------- IndexedDB (مشترك مع السيرفس وركر) ---------- */
function idb() {
  return new Promise((res, rej) => {
    const r = indexedDB.open('shift-db', 1);
    r.onupgradeneeded = () => r.result.createObjectStore('state');
    r.onsuccess = () => res(r.result);
    r.onerror = () => rej(r.error);
  });
}
async function idbSet(k, v) {
  const db = await idb();
  return new Promise((res, rej) => {
    const tx = db.transaction('state', 'readwrite');
    tx.objectStore('state').put(v, k);
    tx.oncomplete = res;
    tx.onerror = () => rej(tx.error);
  });
}
async function idbGet(k) {
  const db = await idb();
  return new Promise((res, rej) => {
    const q = db.transaction('state').objectStore('state').get(k);
    q.onsuccess = () => res(q.result);
    q.onerror = () => rej(q.error);
  });
}

/* ---------- أدوات عرض ---------- */
const AR_D = '٠١٢٣٤٥٦٧٨٩';
const ar = n => String(n).replace(/\d/g, d => AR_D[d]);
const esc = s => String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
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
function cellShort(c) {
  if (!c || c === '?') return '؟';
  if (c === 'off') return 'أجازة';
  if (c === 'online') return 'أونلاين';
  const m = String(c).match(/^(\d{1,2})-(\d{1,2})$/);
  if (m) return ar(m[1] + '-' + m[2]);
  return String(c);
}
function cellText(c) {
  if (!c || c === '?') return 'الشيفت مش واضح في الجدول';
  if (c === 'off') return 'أجازة';
  if (c === 'online') return 'شغل أونلاين (من البيت)';
  const m = String(c).match(/^(\d{1,2})-(\d{1,2})$/);
  if (m) return `من ${ar(m[1])} ${partStart(+m[1])} لـ ${ar(m[2])} ${partEnd(+m[2])}`;
  return String(c);
}
const todayKey = () => JS_DAY[new Date().getDay()];
const tomorrowKey = () => JS_DAY[(new Date().getDay() + 1) % 7];
function todayLabel() {
  const d = new Date();
  return `${DAY_AR[todayKey()]} ${ar(d.getDate())}/${ar(d.getMonth() + 1)}`;
}
function findEmp(name) { return roster && roster.employees.find(e => e.name === name); }

function toast(msg, ms = 2600) {
  const t = document.createElement('div');
  t.className = 'toast';
  t.textContent = msg;
  document.body.appendChild(t);
  setTimeout(() => t.remove(), ms);
}

/* ---------- التنبيهات ---------- */
async function notifStatus() {
  const out = { perm: ('Notification' in window) ? Notification.permission : 'unsupported', periodic: false, installed: false };
  out.installed = window.matchMedia('(display-mode: standalone)').matches || navigator.standalone === true;
  try {
    const reg = await navigator.serviceWorker.ready;
    out.periodic = 'periodicSync' in reg;
  } catch (e) { /* لا شيء */ }
  return out;
}
async function enableNotifications() {
  if (!('Notification' in window) || !('serviceWorker' in navigator)) {
    toast('المتصفح ده مش بيدعم الإشعارات'); return;
  }
  // لا تفعيل صامت لجدول مسودة: لازم الموظف يراجع صفه الأول
  if (roster && roster.unconfirmed) {
    const c = await idbGet('rowConfirmed');
    if (!c || c.name !== me) { showConfirmRowSheet(); return; }
  }
  const perm = await Notification.requestPermission();
  if (perm !== 'granted') { toast('إذن الإشعارات مرفوض — فعّله من إعدادات المتصفح'); render(); return; }
  const reg = await navigator.serviceWorker.ready;
  let periodicOk = false;
  if ('periodicSync' in reg) {
    try { await reg.periodicSync.register('shift-daily', { minInterval: 12 * 60 * 60 * 1000 }); periodicOk = true; }
    catch (e) { periodicOk = false; }
  }
  await idbSet('notif', { on: true, periodicOk });
  await reg.showNotification('تمام', { body: 'الإشعارات اتفعلت (وضع تجريبي): المتصفح هيحاول يبعت تذكير يومي بشيفتك، والتوقيت بيحدده هو.', icon: 'icon.svg', dir: 'rtl', lang: 'ar' });
  render();
}
function showConfirmRowSheet() {
  const emp = findEmp(me);
  const unknownDays = emp ? DAYS.filter(d => !emp[d] || emp[d] === '?').map(d => DAY_AR[d]) : [];
  const bg = document.createElement('div');
  bg.className = 'sheet-bg';
  const sh = document.createElement('div');
  sh.className = 'sheet';
  sh.innerHTML = `<h3>راجع شيفتاتك الأول</h3>
    <p class="note">الجدول لسه مسودة منقولة من الصورة. قبل ما الإشعارات تشتغل، راجع صفك في الجدول وصحّح أي خلية غلط.${unknownDays.length ? `<br><b>أيام غير واضحة في صفك: ${unknownDays.join('، ')}</b> — لو سيبتها، إشعارها هيقول «غير واضح» بدل وقت الشيفت.` : ''}</p>
    <button class="btn" onclick="document.querySelector('.sheet').remove();document.querySelector('.sheet-bg').remove();showTab('table');toggleEdit();">راجع وعدّل صفي</button>
    <button class="btn ghost" id="confirmrow">صفّي صح — فعّل الإشعارات</button>`;
  bg.onclick = () => { bg.remove(); sh.remove(); };
  document.body.appendChild(bg);
  document.body.appendChild(sh);
  document.getElementById('confirmrow').onclick = async () => {
    await idbSet('rowConfirmed', { name: me, at: Date.now() });
    bg.remove(); sh.remove();
    enableNotifications();
  };
}

async function testNotification() {
  try {
    const reg = await navigator.serviceWorker.ready;
    const emp = findEmp(me);
    const body = emp ? `شيفتك النهاردة ${todayLabel()}: ${cellText(emp[todayKey()])}` : 'تنبيه تجريبي';
    await reg.showNotification('تنبيه تجريبي', { body, icon: 'icon.svg', dir: 'rtl', lang: 'ar' });
  } catch (e) { toast('اتأكد الأول إن الإشعارات متفعلة'); }
}

/* ---------- حفظ وتحميل ---------- */
async function saveAll() {
  await idbSet('roster', roster);
  await idbSet('me', me);
}

/* ---------- استيراد / مشاركة ---------- */
function b64encode(str) {
  return btoa(unescape(encodeURIComponent(str))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}
function b64decode(str) {
  str = str.replace(/-/g, '+').replace(/_/g, '/');
  while (str.length % 4) str += '=';
  return decodeURIComponent(escape(atob(str)));
}
function shareLink() {
  return location.origin + location.pathname + '#r=' + b64encode(JSON.stringify(roster));
}
async function copyText(t, okMsg) {
  try { await navigator.clipboard.writeText(t); toast(okMsg); }
  catch (e) { toast('مش قادر أعمل نسخ — حدد النص وانسخه يدويًا'); }
}
async function importRosterFromText(txt) {
  let data;
  try { data = JSON.parse(txt); } catch (e) { toast('النص مش JSON صحيح'); return; }
  if (!data || !Array.isArray(data.employees) || !data.employees.length) { toast('الجدول لازم يحتوي employees'); return; }
  data.employees = data.employees.map(e => {
    const o = { name: String(e.name || '').trim() };
    DAYS.forEach(d => { o[d] = e[d] != null ? String(e[d]) : '?'; });
    return o;
  }).filter(e => e.name);
  if (!data.employees.length) { toast('مفيش أسماء صحيحة في الجدول'); return; }
  roster = data;
  await idbSet('rowConfirmed', null); // أي جدول جديد يلغي التأكيد السابق
  await saveAll();
  toast('تم استيراد الجدول');
  render();
}

/* ---------- الشاشات ---------- */
function showTab(t) { tab = t; render(); }

function render() {
  const app = document.getElementById('app');
  const nav = document.getElementById('nav');
  if (!roster) { nav.style.display = 'none'; app.innerHTML = viewSetup(); return; }
  nav.style.display = 'flex';
  if (!me && roster.employees.length === 0) tab = 'settings';
  document.querySelectorAll('nav.bottom button').forEach(b => b.classList.toggle('on', b.dataset.tab === tab));
  if (!me && tab !== 'settings') { app.innerHTML = viewPick(); return; }
  if (tab === 'home') app.innerHTML = viewHome();
  else if (tab === 'table') app.innerHTML = viewTable();
  else app.innerHTML = viewSettings();
}

function headerHTML(sub) {
  return `<header class="top"><h1>شيفتي</h1><p>${esc(sub || '')}</p></header>`;
}

function viewSetup() {
  return headerHTML('تذكير الشيفت اليومي') + `
  <div class="wrap">
    <div class="card">
      <b>الخطوة ١: حمّل جدول الشيفتات</b>
      <p class="note">الجدول مش متخزن جوّه التطبيق نفسه عشان خصوصية الفريق. المدير يعمل الجدول مرة واحدة وبعدين يبعت لينك المشاركة للفريق، أو كل واحد يلصق الجدول هنا.</p>
      <textarea id="imp" placeholder='الصق الجدول هنا (JSON)'></textarea>
      <button class="btn" onclick="importRosterFromText(document.getElementById('imp').value)">استيراد الجدول</button>
      <button class="btn ghost" onclick="newRoster()">إنشاء جدول جديد يدويًا</button>
    </div>
  </div>`;
}

function viewPick() {
  const btns = roster.employees.map(e => `<button class="name-btn" onclick="pickName('${esc(e.name)}')">${esc(e.name)}</button>`).join('');
  return headerHTML(roster.name || 'جدول الشيفتات') + `
  <div class="wrap">
    <div class="card"><b>الخطوة ٢: اختار اسمك</b>
      <p class="note">كل واحد يختار اسمه مرة واحدة بس على موبايله، وبعدها التطبيق يعرف شيفته لوحده.</p>
      <div class="name-grid">${btns}</div>
    </div>
  </div>`;
}

async function pickName(n) {
  me = n;
  await saveAll();
  tab = 'home';
  render();
}

function viewHome() {
  const emp = findEmp(me);
  const tk = todayKey();
  const c = emp ? emp[tk] : '?';
  const cls = c === 'off' ? 'off' : (!c || c === '?' ? 'unknown' : '');
  const tc = emp ? emp[tomorrowKey()] : '?';
  return headerHTML(`أهلًا ${esc(me)}`) + `
  <div class="wrap">
    <div class="card" style="text-align:center">
      <div class="big-day">شيفتك النهاردة — ${todayLabel()}</div>
      <div class="big-shift ${cls}">${cellText(c)}</div>
      ${(!c || c === '?') ? '<div class="badge">الخلية دي مش مقروءة من صورة الجدول — راجع المدير</div>' : ''}
      <div class="tomorrow">بكرة ${DAY_AR[tomorrowKey()]}: ${cellText(tc)}</div>
    </div>
    <div class="weekstrip">${DAYS.map(d => `
      <div class="daycell ${d === tk ? 'today' : ''}">
        <div class="d">${DAY_AR[d]}</div>
        <div class="s">${cellShort(emp ? emp[d] : '?')}</div>
      </div>`).join('')}
    </div>
    <div class="card">
      <b>التنبيه اليومي <span class="badge">وضع تجريبي</span></b>
      <p class="note">بعد التفعيل والتثبيت على الشاشة الرئيسية، المتصفح <b>يحاول</b> يرسل تذكيرًا يوميًا بشيفتك حتى لو التطبيق مقفول — لكن التوقيت والتكرار بيحددهم المتصفح حسب الاستخدام، والميزة لسه تحت الاختبار على جهاز حقيقي. اعتبرها مساعد إضافي، مش منبه مضمون قبل الشيفت.</p>
      <button class="btn" onclick="enableNotifications()">فعّل الإشعارات</button>
    </div>
  </div>`;
}

function viewTable() {
  const tk = todayKey();
  const rows = roster.employees.map(e => `
    <tr class="${e.name === me ? 'me' : ''}">
      <td class="name">${esc(e.name)}</td>
      ${DAYS.map(d => `<td class="${d === tk ? 'today' : ''} ${editMode ? 'editable' : ''}" ${editMode ? `onclick="editCell('${esc(e.name)}','${d}')"` : ''}>${cellShort(e[d])}</td>`).join('')}
    </tr>`).join('');
  return headerHTML(roster.name || 'الجدول') + `
  <div class="wrap">
    <div class="card" style="padding:12px">
      <div style="display:flex;justify-content:space-between;align-items:center">
        <b>جدول الأسبوع ${roster.week ? '— ' + esc(roster.week) : ''}</b>
        <button class="btn ${editMode ? 'warn' : 'ghost'}" style="width:auto;margin:0;padding:8px 14px;font-size:13px" onclick="toggleEdit()">${editMode ? 'قفل التعديل' : 'تعديل'}</button>
      </div>
      <div class="tbl-wrap"><table>
        <tr><th class="name">الاسم</th>${DAYS.map(d => `<th>${DAY_AR[d]}</th>`).join('')}</tr>
        ${rows}
      </table></div>
      ${roster.unconfirmed ? '<div class="badge">مسودة منقولة من الصورة — راجع الخلايا قبل الاعتماد عليها</div>' : ''}
      ${editMode ? '<p class="note">اضغط على أي خلية عشان تغيّر الشيفت.</p>' : '<p class="note">عمود النهاردة متعلم بالأخضر. وضع التعديل للمدير بس.</p>'}
    </div>
  </div>`;
}

function viewSettings() {
  return headerHTML('الإعدادات') + `
  <div class="wrap">
    <div class="card">
      <b>الاسم</b>
      ${me ? `<div class="emp-row"><input value="${esc(me)}" disabled><button class="del" onclick="changeName()">تغيير</button></div>`
           : `<p class="note">لسه مختارش اسمك. ضيف أسماء الفريق من قسم «الأسماء» تحت، وبعدين اختار اسمك من زر «اليوم» تحت في الشريط.</p>`}
    </div>
    <div class="card">
      <b>الإشعارات</b>
      <div id="nstat"><p class="note">...جاري الفحص</p></div>
      <button class="btn" onclick="enableNotifications()">فعّل الإشعارات</button>
      <button class="btn ghost" onclick="testNotification()">جرّب تنبيه دلوقتي</button>
      <p class="note">شروط وصول التنبيه والتطبيق مقفول: تثبيت التطبيق من قائمة المتصفح (⋮ ← «تثبيت التطبيق» / «إضافة إلى الشاشة الرئيسية») + إذن الإشعارات. وده <b>أفضل مجهود مش مضمون</b>: المتصفح بيحدد التوقيت حسب استخدامك للتطبيق، وممكن يتأخر أو ميوصلش. محتاج اختبار على موبايل حقيقي قبل الاعتماد عليه.</p>
      <div id="install-slot"></div>
    </div>
    <div class="card">
      <b>الجدول</b>
      <div class="emp-row">
        <button class="btn ghost" style="margin:0" onclick="copyText(shareLink(),'اتنسخ لينك المشاركة')">نسخ لينك المشاركة</button>
        <button class="btn ghost" style="margin:0" onclick="copyText(JSON.stringify(roster),'اتنسخ الجدول (JSON)')">نسخ JSON</button>
      </div>
      <p class="note">لينك المشاركة بيحمل الجدول جوّاه، أي حد معاه اللينك يقدر يشوف الأسماء والشيفتات — ابعته للفريق بس.</p>
      <textarea id="imp2" placeholder="الصق جدول جديد هنا (JSON)"></textarea>
      <button class="btn ghost" onclick="importRosterFromText(document.getElementById('imp2').value)">استيراد جدول جديد</button>
      <h2 class="sec">الأسماء</h2>
      <div id="emplist"></div>
      <div class="emp-row"><input id="newname" placeholder="اسم جديد"><button class="add" onclick="addEmp()">إضافة</button></div>
    </div>
    <div class="card">
      <b>حدود النسخة دي بصراحة</b>
      <p class="note">• الجدول متخزن على موبايل كل موظف — أي تعديل محتاج لينك مشاركة جديد.<br>• التنبيه الخلفي تجريبي وتوقيته بيتحكم فيه المتصفح، مش منبه مضمون.<br>• لو محتاجين منبه بالدقيقة قبل الشيفت: ده محتاج تطبيق أندرويد مخصوص (APK) أو سيرفر إشعارات — مش متاح في النسخة دي.<br>• الآيفون غير مدعوم حاليًا.<br>• أندرويد + كروم هو المسار المستهدف.</p>
    </div>
  </div>`;
}

async function refreshSettingsExtras() {
  const el = document.getElementById('nstat');
  if (el) {
    const st = await notifStatus();
    const dot = (ok) => `<span class="dot ${ok}"></span>`;
    el.innerHTML = `
      <div class="stat">${dot(st.perm === 'granted' ? 'ok' : st.perm === 'denied' ? 'no' : 'mid')} إذن الإشعارات: ${st.perm === 'granted' ? 'متفعل' : st.perm === 'denied' ? 'مرفوض — فعّله من إعدادات المتصفح' : 'لسه مش متفعل'}</div>
      <div class="stat">${dot(st.installed ? 'ok' : 'mid')} التثبيت على الشاشة الرئيسية: ${st.installed ? 'مثبت' : 'لسه — ثبّته من قائمة المتصفح ⋮'}</div>
      <div class="stat">${dot(st.periodic ? 'mid' : 'no')} تنبيه يومي في الخلفية (تجريبي): ${st.periodic ? 'مدعوم من المتصفح — التوقيت غير مضمون' : 'المتصفح مش بيدعمه'}</div>`;
  }
  const slot = document.getElementById('install-slot');
  if (slot && deferredInstall) {
    slot.innerHTML = '<button class="btn" onclick="doInstall()">تثبيت التطبيق على الشاشة الرئيسية</button>';
  }
  const list = document.getElementById('emplist');
  if (list && roster) {
    list.innerHTML = roster.employees.map(e => `
      <div class="emp-row"><input value="${esc(e.name)}" disabled><button class="del" onclick="delEmp('${esc(e.name)}')">حذف</button></div>`).join('');
  }
}

async function doInstall() {
  if (!deferredInstall) return;
  deferredInstall.prompt();
  await deferredInstall.userChoice;
  deferredInstall = null;
  render();
}

function toggleEdit() { editMode = !editMode; render(); }

function editCell(name, day) {
  const emp = findEmp(name);
  if (!emp) return;
  const bg = document.createElement('div');
  bg.className = 'sheet-bg';
  bg.onclick = () => bg.parentNode && (bg.remove(), sh.remove());
  const sh = document.createElement('div');
  sh.className = 'sheet';
  sh.innerHTML = `<h3>${esc(name)} — ${DAY_AR[day]}</h3>
    <div class="quick">${QUICK.map(q => `<button onclick="setCell('${esc(name)}','${day}','${q}')">${cellShort(q) === '؟' ? 'غير واضح' : cellShort(q)}</button>`).join('')}</div>
    <div class="row"><input id="customcell" placeholder="أو اكتب شيفت مخصص (مثال: 12-8)"><button class="add" onclick="setCell('${esc(name)}','${day}',document.getElementById('customcell').value)">حفظ</button></div>`;
  bg.onclick = () => { bg.remove(); sh.remove(); };
  document.body.appendChild(bg);
  document.body.appendChild(sh);
}
async function setCell(name, day, val) {
  const emp = findEmp(name);
  if (!emp) return;
  emp[day] = String(val || '?').trim() || '?';
  await saveAll();
  render();
  toast('اتحفظ');
}

async function addEmp() {
  const inp = document.getElementById('newname');
  const n = (inp.value || '').trim();
  if (!n) return;
  if (findEmp(n)) { toast('الاسم موجود بالفعل'); return; }
  const e = { name: n };
  DAYS.forEach(d => e[d] = '?');
  roster.employees.push(e);
  await saveAll();
  render();
}
async function delEmp(n) {
  roster.employees = roster.employees.filter(e => e.name !== n);
  if (me === n) me = null;
  await saveAll();
  render();
}
function changeName() { me = null; saveAll().then(render); }
function newRoster() {
  roster = { name: 'جدول الشيفتات', week: '', employees: [] };
  me = null;
  saveAll().then(() => { tab = 'settings'; render(); toast('ضيف أسماء الفريق من الإعدادات، وبعدها عدّل الجدول'); });
}

/* ---------- تشغيل ---------- */
(async function boot() {
  if ('serviceWorker' in navigator) {
    try { await navigator.serviceWorker.register('sw.js'); } catch (e) { /* غير حرج */ }
  }
  window.addEventListener('beforeinstallprompt', e => { e.preventDefault(); deferredInstall = e; });

  const h = location.hash;
  const stored = await idbGet('roster');
  if (h.startsWith('#r=')) {
    try {
      const data = JSON.parse(b64decode(h.slice(3)));
      if (data && Array.isArray(data.employees) && data.employees.length) {
        roster = data;
        me = await idbGet('me');
        if (me && !findEmp(me)) me = null;
        await saveAll();
        history.replaceState(null, '', location.pathname);
        toast('تم تحميل الجدول من اللينك');
      }
    } catch (e) { /* تجاهل الهاش التالف */ }
  }
  if (!roster && stored) { roster = stored; me = await idbGet('me'); }
  render();
  if (tab === 'settings') refreshSettingsExtras();
})();

/* إعادة رسم إضافات الإعدادات بعد كل render */
const _render = render;
render = function () { _render(); if (tab === 'settings' && roster && me) refreshSettingsExtras(); };
