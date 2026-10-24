#!/usr/bin/env node
/* Node-based unit test for shell-tpl/src/com/html2apk/shell/shim.js.
 * Runs the real shim against a fake window/document + fake HT2A bridge.
 * No dependencies. Usage: node tests/shim-test.js
 */
'use strict';

const fs = require('fs');
const vm = require('vm');
const path = require('path');
const { Blob, File } = require('buffer');

const ROOT = path.resolve(__dirname, '..');
const SHIM = fs.readFileSync(
  path.join(ROOT, 'shell-tpl', 'src', 'com', 'html2apk', 'shell', 'shim.js'), 'utf8');

let failures = 0;
function ok(cond, msg) {
  if (cond) { console.log('  PASS ' + msg); }
  else { failures++; console.log('  FAIL ' + msg); }
}
function eq(a, b, msg) { ok(a === b, msg + ' (got ' + JSON.stringify(a) + ')'); }
function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

class FakeStorage {
  constructor() { this._m = new Map(); }
  get length() { return this._m.size; }
  key(i) { return Array.from(this._m.keys())[i] ?? null; }
  getItem(k) { return this._m.has(String(k)) ? this._m.get(String(k)) : null; }
  setItem(k, v) { this._m.set(String(k), String(v)); }
  removeItem(k) { this._m.delete(String(k)); }
  clear() { this._m.clear(); }
}

function makeEl(tag) {
  const el = {
    tagName: String(tag).toUpperCase(),
    id: '', name: '', type: '', value: '', checked: false,
    options: [], selectedIndex: 0, innerText: '', textContent: '',
    attributes: {}, children: [], style: {}, parentNode: null,
    setAttribute(k, v) { this.attributes[k] = String(v); },
    getAttribute(k) { return Object.prototype.hasOwnProperty.call(this.attributes, k) ? this.attributes[k] : null; },
    hasAttribute(k) { return Object.prototype.hasOwnProperty.call(this.attributes, k); },
    appendChild(c) { this.children.push(c); c.parentNode = this; return c; },
    removeChild(c) { this.children = this.children.filter(x => x !== c); },
    querySelector() { return null; },
    getBoundingClientRect() { return { width: 0, height: 0, top: 0, left: 0 }; },
    addEventListener() {},
    dispatchEvent() { return true; },
    click() {},
    focus() {},
    closest() { return null; }
  };
  return el;
}

function makeWorld(opts) {
  opts = opts || {};
  const native = { local: new Map(), session: new Map() };
  Object.entries(opts.seedNative || {}).forEach(([k, v]) => native.local.set(k, v));

  const saved = [];
  const toasts = [];
  const logs = [];
  let pendingPick = null;
  let saveCurrent = null;
  const urlBlobs = Object.assign({}, opts.urlBlobs || {});

  const body = makeEl('body');
  body.innerText = 'Hello page';
  body.querySelector = () => null;
  const els = opts.controls || [];
  const listeners = {};
  const docListeners = {};

  const document = {
    readyState: 'complete',
    visibilityState: 'visible',
    title: 'Test Page',
    body: body,
    documentElement: makeEl('html'),
    createElement: makeEl,
    getElementById: () => null,
    querySelector: () => null,
    querySelectorAll: () => els,
    addEventListener(type, fn) { (docListeners[type] = docListeners[type] || []).push(fn); },
    removeEventListener() {},
    baseURI: 'https://appassets.androidplatform.net/index.html'
  };

  const B = {
    getInfo() {
      return JSON.stringify({
        version: '1.2.0', debug: !!opts.debug, formRestore: true,
        entry: 'index.html', origin: 'https://appassets.androidplatform.net'
      });
    },
    storageGet(ns, k) { return native[ns].has(k) ? native[ns].get(k) : null; },
    storageSet(ns, k, v) { native[ns].set(k, String(v)); return ''; },
    storageRemove(ns, k) { native[ns].delete(k); return true; },
    storageClear(ns) { native[ns].clear(); return true; },
    storageKeys(ns) { return JSON.stringify(Array.from(native[ns].keys())); },
    storageInfo() { return JSON.stringify({ localBytes: 0 }); },
    saveBegin(name, mime) {
      saveCurrent = { name, mime, chunks: [] };
      return 'tok1';
    },
    saveChunk(token, b64) {
      if (!saveCurrent) return 'no session';
      saveCurrent.chunks.push(Buffer.from(b64, 'base64'));
      return '';
    },
    saveEnd(token) {
      if (!saveCurrent) return JSON.stringify({ ok: false, error: 'no session' });
      const data = Buffer.concat(saveCurrent.chunks);
      saved.push({ name: saveCurrent.name, mime: saveCurrent.mime, data });
      const res = JSON.stringify({ ok: true, path: 'Download/HTML2APK/' + saveCurrent.name, bytes: data.length });
      saveCurrent = null;
      return res;
    },
    saveAbort() { saveCurrent = null; },
    pickFiles(mimes, multiple, id) { pendingPick = { mimes, multiple, id }; },
    toast(m) { toasts.push(String(m)); },
    copy() {},
    log(level, m) { logs.push(String(m)); },
    reload() {}, exitApp() {}, openUrl() {}, setKeepScreenOn() {},
    getInsets() { return '{}'; }, getLastError() { return ''; },
    requestStoragePermission() {}
  };

  const fakeLocal = new FakeStorage();
  const fakeSession = new FakeStorage();
  Object.entries(opts.seedReal || {}).forEach(([k, v]) => fakeLocal.setItem(k, v));

  const win = {
    HT2A: B,
    localStorage: fakeLocal,
    sessionStorage: fakeSession,
    Storage: FakeStorage,
    location: {
      href: 'https://appassets.androidplatform.net/index.html',
      pathname: '/index.html',
      origin: 'https://appassets.androidplatform.net',
      protocol: 'https:',
      host: 'appassets.androidplatform.net'
    },
    navigator: { userAgent: 'NodeTest' },
    performance: {
      getEntriesByType() { return [{ type: opts.navType || 'navigate' }]; },
      navigation: { type: 0 }
    },
    addEventListener(type, fn) { (listeners[type] = listeners[type] || []).push(fn); },
    removeEventListener() {},
    setTimeout, clearTimeout, setInterval, clearInterval,
    btoa, atob, Blob, File, URL, DOMException,
    Event: class FakeEvent {
      constructor(type, o) { this.type = type; this.bubbles = !!(o && o.bubbles); }
    },
    fetch(url) {
      const blob = urlBlobs[url];
      return Promise.resolve({ ok: true, status: 200, blob: () => Promise.resolve(blob || new Blob(['{}'])) });
    },
    open() { return null; },
    HTMLAnchorElement: function FakeAnchor() {},
    document
  };
  win.window = win;
  win.self = win;
  win.globalThis = win;
  win.HTMLAnchorElement.prototype.click = function () { this.__origClicked = true; };
  win.XMLHttpRequest = function () {};
  win.console = console;

  const ctx = vm.createContext(win, { name: 'shim-test' });
  vm.runInContext(SHIM, ctx, { filename: 'shim.js' });

  return {
    ctx, win, native, saved, toasts, logs, els, docListeners, listeners,
    fakeLocal, fakeSession, B,
    pick() { return pendingPick; },
    clickHandler() { return (docListeners.click || [])[0]; }
  };
}

async function main() {
  console.log('== shim syntax/load ==');
  const w = makeWorld({
    seedReal: { legacy: 'old-value' },
    seedNative: { existing: 'native-value' }
  });
  ok(w.ctx.HT2A_WEB && typeof w.ctx.HT2A_WEB.saveText === 'function', 'shim loaded and exposed HT2A_WEB');
  eq(w.native.local.get('legacy'), 'old-value', 'existing real localStorage migrated to native');
  eq(w.ctx.localStorage.getItem('existing'), 'native-value', 'native value readable through localStorage');

  console.log('== localStorage patch / quota bypass ==');
  const big = 'x'.repeat(2 * 1024 * 1024);
  w.ctx.localStorage.setItem('big', big);
  eq(w.native.local.get('big').length, big.length, '2MB setItem routed to native store');
  eq(w.ctx.localStorage.getItem('big').length, big.length, '2MB getItem reads native value');
  eq(w.ctx.localStorage.length, 3, 'length counts native keys');
  w.ctx.localStorage.removeItem('big');
  eq(w.ctx.localStorage.getItem('big'), null, 'removeItem works');
  eq(w.ctx.HT2A_WEB.storage.get('legacy'), 'old-value', 'HT2A_WEB.storage helper reads native');

  console.log('== blob download bridge ==');
  const r1 = await w.ctx.HT2A_WEB.saveText('data.json', '{"hello":1}', 'application/json');
  ok(r1 && r1.path.indexOf('data.json') >= 0, 'saveText resolved with saved path');
  eq(w.saved.length, 1, 'one save call');
  eq(w.saved[0].name, 'data.json', 'filename');
  eq(w.saved[0].data.toString('utf8'), '{"hello":1}', 'chunked payload intact');

  console.log('== data: URL download ==');
  await w.ctx.__ht2aSaveBlobUrl('data:application/json;base64,' + Buffer.from('{"a":2}').toString('base64'));
  eq(w.saved.length, 2, 'data URL saved');
  eq(w.saved[1].data.toString('utf8'), '{"a":2}', 'data URL decoded');

  console.log('== File System Access: save ==');
  const handle = await w.ctx.showSaveFilePicker({
    suggestedName: 'export',
    types: [{ description: 'JSON', accept: { 'application/json': ['.json'] } }]
  });
  eq(handle.name, 'export.json', 'extension appended from accept type');
  const writable = await handle.createWritable();
  await writable.write('{"fs":true}');
  await writable.close();
  eq(w.saved.length, 3, 'showSaveFilePicker close saved file');
  eq(w.saved[2].name, 'export.json', 'saved with suggested name');
  eq(w.saved[2].data.toString('utf8'), '{"fs":true}', 'writable content');

  console.log('== File System Access: open ==');
  const openP = w.ctx.showOpenFilePicker({ multiple: false });
  const pending = w.pick();
  ok(pending && pending.id, 'native picker invoked');
  w.ctx.__ht2aPickResult(pending.id, JSON.stringify({
    files: [{ name: 'a.txt', mime: 'text/plain', size: 3, base64: Buffer.from('abc').toString('base64') }]
  }));
  const handles = await openP;
  eq(handles.length, 1, 'one handle returned');
  const file = await handles[0].getFile();
  eq(file.name, 'a.txt', 'picked file name');
  eq(await file.text(), 'abc', 'picked file content');

  console.log('== anchor download interception ==');
  const a = makeEl('a');
  a.setAttribute('href', 'https://appassets.androidplatform.net/data.json');
  a.setAttribute('download', 'download.json');
  w.win.HTMLAnchorElement.prototype.click.call(a);
  await sleep(30);
  eq(w.saved.length, 4, 'anchor click saved same-origin file');
  eq(w.saved[3].name, 'download.json', 'download attribute used as filename');

  console.log('== data URL click listener ==');
  const a2 = makeEl('a');
  a2.setAttribute('href', 'data:text/plain;base64,' + Buffer.from('clicked').toString('base64'));
  const ev = { defaultPrevented: false, button: 0, target: a2, preventDefault() { this.defaultPrevented = true; }, stopPropagation() {} };
  w.clickHandler()(ev);
  await sleep(30);
  ok(ev.defaultPrevented, 'click prevented');
  eq(w.saved.length, 5, 'clicked data URL saved');
  eq(w.saved[4].data.toString('utf8'), 'clicked', 'clicked data content');

  console.log('== form restore on reload ==');
  const input = makeEl('input');
  input.id = 'q';
  input.type = 'text';
  input.value = '';
  const nativeForm = {
    '#q': { t: 'text', v: 'restored value' }
  };
  const w2 = makeWorld({
    seedNative: { ['__ht2a_form:/index.html']: JSON.stringify(nativeForm) },
    controls: [input],
    navType: 'reload'
  });
  await sleep(120);
  w2.docListeners.input && w2.docListeners.input[0] && w2.docListeners.input[0]({});
  eq(input.value, 'restored value', 'form value restored after reload');

  console.log('== missing bridge safety ==');
  const w3 = makeWorld({});
  let threw = false;
  try { w3.ctx.HT2A_WEB.storage.set('k', 'v'); } catch (e) { threw = true; }
  ok(!threw, 'storage helper does not throw on success');

  console.log('');
  if (failures) {
    console.log('RESULT: FAIL (' + failures + ' failures)');
    process.exit(1);
  }
  console.log('RESULT: PASS');
}

main().catch(e => { console.error(e); process.exit(2); });
