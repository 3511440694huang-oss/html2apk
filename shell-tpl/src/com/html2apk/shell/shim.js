/* HTML2APK runtime shim v1.2
 * Injected into every HTML page served by the product shell.
 * Adds: large native-backed localStorage, JSON/file downloads, File System
 * Access polyfills, form restore on reload, and error diagnostics.
 * ES5-compatible on purpose (old WebView on Android 7).
 */
(function () {
  'use strict';

  var B = window.HT2A;
  if (!B || window.__HT2A_SHIM__) { return; }
  window.__HT2A_SHIM__ = '1.2.0';

  var INFO = {};
  try { INFO = JSON.parse(B.getInfo() || '{}') || {}; } catch (e) { INFO = {}; }
  window.__HT2A_INFO__ = INFO;
  var DEBUG = !!INFO.debug;

  var errors = [];
  window.HT2AErrors = errors;

  function str(v) { return v === null || v === undefined ? '' : String(v); }
  function now() { return Date.now ? Date.now() : new Date().getTime(); }
  function log(level, msg) { try { B.log(level, str(msg)); } catch (e) {} }
  function toast(msg) { try { B.toast(str(msg)); } catch (e) {} }
  function addError(msg) {
    try {
      errors.push({ t: now(), m: str(msg) });
      if (errors.length > 30) { errors.shift(); }
      log('error', msg);
    } catch (e) {}
  }

  /* ------------------------------------------------------------------ *
   * 1. Native-backed storage (unlimited quota for getItem/setItem users)
   * ------------------------------------------------------------------ */

  var store = (function () {
    var MAX_CACHE = 24 * 1024 * 1024;
    var cache = Object.create ? Object.create(null) : {};
    var cacheBytes = 0;
    var keyCache = { local: null, session: null };

    function keys(ns) {
      var k = keyCache[ns];
      if (k) { return k; }
      var raw = null;
      try { raw = B.storageKeys(ns); } catch (e) { raw = '[]'; }
      try { k = JSON.parse(raw || '[]'); } catch (e) { k = []; }
      if (!k || typeof k.length !== 'number') { k = []; }
      keyCache[ns] = k;
      return k;
    }
    function invalidate(ns) { keyCache[ns] = null; }

    function remember(k, v) {
      var old = cache[k];
      if (old !== undefined) { cacheBytes -= (old.length + k.length) * 2; }
      cache[k] = v;
      cacheBytes += (v.length + k.length) * 2;
      if (cacheBytes > MAX_CACHE) {
        // drop oldest inserted keys until under half the cap
        var ks = [];
        for (var n in cache) { if (Object.prototype.hasOwnProperty.call(cache, n)) { ks.push(n); } }
        for (var i = 0; i < ks.length && cacheBytes > MAX_CACHE / 2; i++) {
          cacheBytes -= (cache[ks[i]].length + ks[i].length) * 2;
          delete cache[ks[i]];
        }
      }
    }
    function forget(k) {
      if (cache[k] !== undefined) {
        cacheBytes -= (cache[k].length + k.length) * 2;
        delete cache[k];
      }
    }

    function get(ns, k) {
      k = str(k);
      if (cache[k] !== undefined) { return cache[k]; }
      var v = null;
      try { v = B.storageGet(ns, k); } catch (e) { v = null; }
      if (v !== null && v !== undefined) { remember(k, v); return v; }
      return null;
    }
    function set(ns, k, v) {
      k = str(k); v = str(v);
      var err = '';
      try { err = B.storageSet(ns, k, v); } catch (e) { err = str(e && e.message ? e.message : e); }
      if (err) { return err; }
      remember(k, v);
      var ks = keys(ns);
      var found = false;
      for (var i = 0; i < ks.length; i++) { if (ks[i] === k) { found = true; break; } }
      if (!found) { ks.push(k); }
      return '';
    }
    function remove(ns, k) {
      k = str(k);
      try { B.storageRemove(ns, k); } catch (e) {}
      forget(k);
      var ks = keys(ns);
      for (var i = 0; i < ks.length; i++) {
        if (ks[i] === k) { ks.splice(i, 1); break; }
      }
    }
    function clear(ns) {
      try { B.storageClear(ns); } catch (e) {}
      cache = Object.create ? Object.create(null) : {};
      cacheBytes = 0;
      invalidate(ns);
    }
    return {
      get: get, set: set, remove: remove, clear: clear,
      keys: keys, invalidate: invalidate
    };
  })();

  function quotaError(msg) {
    var e;
    try { e = new DOMException(msg || 'Storage write failed', 'QuotaExceededError'); }
    catch (x) { e = new Error(msg || 'Storage write failed'); e.name = 'QuotaExceededError'; }
    return e;
  }

  // Mirror real Storage values (small ones only) into the native store at start.
  // This is what makes data written by older versions (or by direct
  // localStorage.foo = ... access) survive the 5MB quota and app restarts.
  var realLocal = null, realSession = null;
  try { realLocal = window.localStorage; } catch (e) {}
  try { realSession = window.sessionStorage; } catch (e) {}

  function migrate() {
    if (!realLocal) { return; }
    var native = store.keys('local');
    var have = {};
    for (var i = 0; i < native.length; i++) { have[native[i]] = 1; }
    var rk = [];
    try {
      for (var j = 0; j < realLocal.length; j++) { rk.push(realLocal.key(j)); }
    } catch (e) {}
    for (var n = 0; n < rk.length; n++) {
      var k = rk[n];
      if (k === null || have[k]) { continue; }
      try {
        var v = realLocal.getItem(k);
        if (v !== null && v !== undefined) { store.set('local', k, v); }
      } catch (e) {}
    }
  }
  function hydrate() {
    if (!realLocal) { return; }
    var budget = 3 * 1024 * 1024, used = 0;
    var ks = store.keys('local');
    for (var i = 0; i < ks.length; i++) {
      var k = ks[i];
      var v;
      try { v = store.get('local', k); } catch (e) { v = null; }
      if (v === null) { continue; }
      if (v.length > 512 * 1024) { continue; }   // too big for the 5MB area
      if (used + v.length > budget) { break; }
      try {
        realLocal.setItem(k, v);
        used += v.length;
      } catch (e) { break; }
    }
  }
  try { migrate(); hydrate(); } catch (e) { log('error', 'storage migrate: ' + e); }

  // Patch the Storage prototype: methods can be intercepted, the exotic
  // named/indexed properties cannot (localStorage.foo = 1 stays real).
  function installStoragePatch() {
    if (!window.Storage || !window.Storage.prototype) { return; }
    var proto = Storage.prototype;
    var orig = {
      getItem: proto.getItem, setItem: proto.setItem,
      removeItem: proto.removeItem, clear: proto.clear, key: proto.key
    };
    var lengthDesc = null;
    try { lengthDesc = Object.getOwnPropertyDescriptor(proto, 'length'); } catch (e) {}
    var realLen = 0;
    try { realLen = realLocal ? realLocal.length : 0; } catch (e) {}

    function which(self) {
      try { if (self === window.localStorage) { return 'local'; } } catch (e) {}
      try { if (self === window.sessionStorage) { return 'session'; } } catch (e) {}
      return null;
    }

    proto.getItem = function (key) {
      var ns = which(this);
      if (!ns) { return orig.getItem.call(this, key); }
      key = str(key);
      var v = store.get(ns, key);
      if (v !== null) { return v; }
      // fall back to the real storage (e.g. a value written via direct property)
      var r = null;
      try { r = orig.getItem.call(this, key); } catch (e) {}
      if (r !== null && r !== undefined) { store.set(ns, key, r); }
      return r === undefined ? null : r;
    };
    proto.setItem = function (key, value) {
      var ns = which(this);
      key = str(key); value = str(value);
      if (!ns) { return orig.setItem.call(this, key, value); }
      var err = store.set(ns, key, value);
      // Keep small values in the real Storage too, so direct property access
      // keeps working for ordinary app settings.
      if (!err && value.length <= 512 * 1024) {
        try { orig.setItem.call(this, key, value); } catch (e) {}
      }
      if (err) { throw quotaError(err); }
    };
    proto.removeItem = function (key) {
      var ns = which(this);
      if (!ns) { return orig.removeItem.call(this, key); }
      store.remove(ns, key);
      try { orig.removeItem.call(this, key); } catch (e) {}
    };
    proto.clear = function () {
      var ns = which(this);
      if (!ns) { return orig.clear.call(this); }
      store.clear(ns);
      try { orig.clear.call(this); } catch (e) {}
    };
    proto.key = function (index) {
      var ns = which(this);
      if (!ns) { return orig.key.call(this, index); }
      var ks = store.keys(ns);
      return index >= 0 && index < ks.length ? ks[index] : null;
    };
    try {
      Object.defineProperty(proto, 'length', {
        configurable: true,
        get: function () {
          var ns = which(this);
          if (!ns) {
            return lengthDesc && lengthDesc.get ? lengthDesc.get.call(this) : realLen;
          }
          return store.keys(ns).length;
        }
      });
    } catch (e) { log('error', 'length patch: ' + e); }
  }
  try { installStoragePatch(); } catch (e) { log('error', 'storage patch: ' + e); }

  var publicStorage = {
    get: function (k) { return store.get('local', k); },
    set: function (k, v) {
      var err = store.set('local', k, v);
      if (err) { throw quotaError(err); }
    },
    remove: function (k) { store.remove('local', k); },
    keys: function () { return store.keys('local').slice(); },
    clear: function () { store.clear('local'); },
    info: function () { try { return B.storageInfo(); } catch (e) { return '{}'; } }
  };

  /* ------------------------------------------------------------------ *
   * 2. File saving / downloads
   * ------------------------------------------------------------------ */

  var CHUNK = 256 * 1024;

  function readArrayBuffer(blob) {
    if (blob && blob.arrayBuffer) {
      return blob.arrayBuffer().catch(function () { return readViaReader(blob); });
    }
    return readViaReader(blob);
  }
  function readViaReader(blob) {
    return new Promise(function (resolve, reject) {
      var fr = new FileReader();
      fr.onload = function () { resolve(fr.result); };
      fr.onerror = function () { reject(fr.error || new Error('read failed')); };
      fr.readAsArrayBuffer(blob);
    });
  }
  function bytesToBase64(bytes) {
    var s = '', step = 8192;
    for (var i = 0; i < bytes.length; i += step) {
      s += String.fromCharCode.apply(null, bytes.subarray(i, i + step));
    }
    return btoa(s);
  }
  function base64ToBytes(b64) {
    var bin = atob(b64), n = bin.length, out = new Uint8Array(n);
    for (var i = 0; i < n; i++) { out[i] = bin.charCodeAt(i); }
    return out;
  }
  function sanitizeName(name, mime) {
    name = str(name).replace(/[\\/:*?"<>|\u0000-\u001f]/g, '_').replace(/^\s+|\s+$/g, '');
    if (!name || name === '.' || name === '..') { name = 'download'; }
    if (name.length > 120) {
      var dot = name.lastIndexOf('.');
      var ext = dot > 0 ? name.substring(dot) : '';
      name = name.substring(0, 120 - ext.length) + ext;
    }
    if (name.indexOf('.') < 0) { name += extensionFor(mime); }
    return name;
  }
  function extensionFor(mime) {
    mime = str(mime).toLowerCase();
    if (mime.indexOf('json') >= 0) { return '.json'; }
    if (mime.indexOf('csv') >= 0) { return '.csv'; }
    if (mime.indexOf('html') >= 0) { return '.html'; }
    if (mime.indexOf('xml') >= 0) { return '.xml'; }
    if (mime.indexOf('javascript') >= 0) { return '.js'; }
    if (mime.indexOf('css') >= 0) { return '.css'; }
    if (mime.indexOf('plain') >= 0 || mime.indexOf('text/') === 0) { return '.txt'; }
    if (mime.indexOf('zip') >= 0) { return '.zip'; }
    if (mime.indexOf('pdf') >= 0) { return '.pdf'; }
    if (mime.indexOf('png') >= 0) { return '.png'; }
    if (mime.indexOf('jpeg') >= 0) { return '.jpg'; }
    return '.bin';
  }
  function guessName(href, mime, a) {
    var n = a ? a.getAttribute('download') : '';
    if (n) { return sanitizeName(n, mime); }
    try {
      var u = new URL(href, location.href);
      var base = u.pathname.substring(u.pathname.lastIndexOf('/') + 1);
      if (base && base.indexOf('.') > 0) { return sanitizeName(decodeURIComponent(base), mime); }
    } catch (e) {}
    var title = sanitizeName(document.title || 'download', mime);
    if (title.indexOf('.') > 0) { title = title.substring(0, title.lastIndexOf('.')); }
    return sanitizeName(title, mime);
  }

  function saveBlob(blob, name, mime) {
    return new Promise(function (resolve, reject) {
      if (!blob) { reject(new Error('没有可保存的数据')); return; }
      mime = mime || blob.type || 'application/octet-stream';
      name = sanitizeName(name, mime);
      var token = '';
      try { token = B.saveBegin(name, mime); } catch (e) { reject(e); return; }
      if (!token || token.indexOf('ERR:') === 0) {
        reject(new Error(token ? token.substring(4) : '无法创建文件'));
        return;
      }
      var offset = 0;
      function finish() {
        var res = '', obj = null;
        try { res = B.saveEnd(token); } catch (e) { res = ''; }
        try { obj = JSON.parse(res || '{}'); } catch (e) { obj = null; }
        if (obj && obj.ok) { resolve(obj); }
        else { reject(new Error((obj && obj.error) || '保存失败')); }
      }
      function step() {
        if (offset >= blob.size) { finish(); return; }
        var end = Math.min(offset + CHUNK, blob.size);
        var slice = blob.slice(offset, end);
        offset = end;
        readArrayBuffer(slice).then(function (buf) {
          var err = '';
          try { err = B.saveChunk(token, bytesToBase64(new Uint8Array(buf))); } catch (e) { err = str(e); }
          if (err) {
            try { B.saveAbort(token); } catch (e) {}
            reject(new Error(err));
            return;
          }
          step();
        }, function (e) {
          try { B.saveAbort(token); } catch (x) {}
          reject(e);
        });
      }
      step();
    });
  }

  function saveDataUrl(url, name) {
    return new Promise(function (resolve, reject) {
      var comma = url.indexOf(',');
      if (comma < 0) { reject(new Error('无效的 data URL')); return; }
      var meta = url.substring(5, comma), data = url.substring(comma + 1);
      var mime = meta.split(';')[0] || 'text/plain';
      try {
        var blob;
        if (/;base64/i.test(meta)) {
          var bin = atob(decodeURIComponent(data));
          var bytes = new Uint8Array(bin.length);
          for (var i = 0; i < bin.length; i++) { bytes[i] = bin.charCodeAt(i); }
          blob = new Blob([bytes], { type: mime });
        } else {
          blob = new Blob([decodeURIComponent(data)], { type: mime });
        }
        saveBlob(blob, name || guessName(url, mime), mime).then(resolve, reject);
      } catch (e) { reject(e); }
    });
  }

  function fetchBlob(url) {
    if (window.fetch) {
      return fetch(url, { credentials: 'same-origin' }).then(function (r) {
        if (!r.ok) { throw new Error('HTTP ' + r.status); }
        return r.blob();
      });
    }
    return new Promise(function (resolve, reject) {
      var xhr = new XMLHttpRequest();
      xhr.open('GET', url, true);
      xhr.responseType = 'blob';
      xhr.onload = function () {
        if (xhr.status >= 200 && xhr.status < 300) { resolve(xhr.response); }
        else { reject(new Error('HTTP ' + xhr.status)); }
      };
      xhr.onerror = function () { reject(new Error('网络读取失败')); };
      xhr.send();
    });
  }

  function saveHref(url, name) {
    if (/^data:/i.test(url)) { return saveDataUrl(url, name); }
    return fetchBlob(url).then(function (blob) {
      return saveBlob(blob, name || guessName(url, blob.type), blob.type);
    });
  }

  function anchorTarget(a) {
    var href = a.getAttribute('href') || '';
    if (!href || href.charAt(0) === '#') { return null; }
    var isBlob = /^blob:/i.test(href), isData = /^data:/i.test(href);
    if (!isBlob && !isData && !a.hasAttribute('download')) { return null; }
    var abs;
    try { abs = new URL(href, location.href); } catch (e) { return null; }
    if (!isBlob && !isData && abs.origin !== location.origin) {
      return null; // cross-origin: leave it to the native DownloadListener
    }
    return { url: abs.href, name: guessName(abs.href, '', a) };
  }

  function handleAnchor(a) {
    var t = anchorTarget(a);
    if (!t) { return false; }
    saveHref(t.url, t.name).then(function (r) {
      log('info', 'saved ' + (r.path || r.uri || t.name));
    }, function (e) {
      addError('保存失败: ' + (e && e.message ? e.message : e));
      toast('保存失败: ' + (e && e.message ? e.message : e));
    });
    return true;
  }

  document.addEventListener('click', function (ev) {
    if (ev.defaultPrevented || (ev.button !== undefined && ev.button !== 0)) { return; }
    var el = ev.target;
    while (el && el !== document && el.tagName !== 'A') { el = el.parentNode; }
    if (!el || el.tagName !== 'A') { return; }
    if (handleAnchor(el)) {
      ev.preventDefault();
      ev.stopPropagation();
    }
  }, true);

  if (window.HTMLAnchorElement && HTMLAnchorElement.prototype) {
    var origClick = HTMLAnchorElement.prototype.click;
    HTMLAnchorElement.prototype.click = function () {
      try { if (handleAnchor(this)) { return; } } catch (e) {}
      return origClick.apply(this, arguments);
    };
  }
  if (window.open) {
    var origOpen = window.open;
    window.open = function (url) {
      if (typeof url === 'string' && /^(blob|data):/i.test(url)) {
        saveHref(url, guessName(url, '')).then(function () {}, function (e) {
          toast('保存失败: ' + (e && e.message ? e.message : e));
        });
        return null;
      }
      return origOpen.apply(window, arguments);
    };
  }

  // Used by the native DownloadListener for blob: URLs it cannot resolve.
  window.__ht2aSaveBlobUrl = function (url) { return saveHref(url, guessName(url, '')); };
  window.__ht2aSaveBlob = function (blob, name) { return saveBlob(blob, name || 'download'); };

  /* ------------------------------------------------------------------ *
   * 3. File System Access API polyfill
   * ------------------------------------------------------------------ */

  var pickSeq = 0, pickResolvers = {};
  window.__ht2aPickResult = function (id, json) {
    var fn = pickResolvers[id];
    if (!fn) { return; }
    delete pickResolvers[id];
    var data = {};
    try { data = JSON.parse(json || '{}'); } catch (e) {}
    fn(data);
  };

  function pickNative(mimes, multiple) {
    return new Promise(function (resolve, reject) {
      var id = 'p' + (++pickSeq) + '_' + now();
      pickResolvers[id] = function (data) {
        if (data && data.canceled) {
          var e;
          try { e = new DOMException('The user aborted a request.', 'AbortError'); }
          catch (x) { e = new Error('aborted'); e.name = 'AbortError'; }
          reject(e);
          return;
        }
        if (data && data.error) { reject(new Error(data.error)); return; }
        resolve((data && data.files) || []);
      };
      try { B.pickFiles(mimes || '*/*', !!multiple, id); }
      catch (e) {
        delete pickResolvers[id];
        reject(e);
      }
    });
  }

  function makeHandle(file) {
    var bytes = base64ToBytes(file.base64 || '');
    var mime = file.mime || 'application/octet-stream';
    var name = file.name || 'file';
    var f = null;
    try { f = new File([bytes], name, { type: mime, lastModified: now() }); }
    catch (e) { f = new Blob([bytes], { type: mime }); f.name = name; }
    return {
      kind: 'file',
      name: name,
      __ht2a: true,
      isSameEntry: function (other) { return other === this; },
      queryPermission: function () { return Promise.resolve('granted'); },
      requestPermission: function () { return Promise.resolve('granted'); },
      getFile: function () { return Promise.resolve(f); },
      createWritable: function () { return Promise.reject(new Error('writable not supported on opened file')); }
    };
  }

  if (!window.showSaveFilePicker) {
    window.showSaveFilePicker = function (opts) {
      opts = opts || {};
      var suggested = opts.suggestedName || 'download';
      var mime = 'application/octet-stream', exts = [];
      var types = opts.types || [];
      if (types.length) {
        var acc = types[0].accept || {};
        var keys = Object.keys(acc);
        if (keys.length) {
          mime = keys[0];
          exts = acc[keys[0]] || [];
        }
      }
      if (exts.length && suggested.indexOf('.') < 0) { suggested += exts[0]; }
      var chunks = [];
      var handle = {
        kind: 'file',
        name: suggested,
        __ht2a: true,
        isSameEntry: function (o) { return o === this; },
        queryPermission: function () { return Promise.resolve('granted'); },
        requestPermission: function () { return Promise.resolve('granted'); },
        getFile: function () {
          return Promise.resolve(new File(chunks, suggested, { type: mime }));
        },
        createWritable: function () {
          var pos = 0;
          var stream = {
            write: function (data) {
              if (data && typeof data === 'object' && data.type) {
                if (data.type === 'seek' || data.type === 'truncate') { return Promise.resolve(); }
                if (data.type === 'write') { data = data.data; }
              }
              chunks.push(data); pos += 1;
              return Promise.resolve();
            },
            seek: function () { return Promise.resolve(); },
            truncate: function () { return Promise.resolve(); },
            abort: function () { chunks = []; return Promise.resolve(); },
            close: function () {
              var blob = new Blob(chunks, { type: mime });
              return saveBlob(blob, suggested, mime).then(function () {});
            }
          };
          return Promise.resolve(stream);
        }
      };
      return Promise.resolve(handle);
    };
  }

  if (!window.showOpenFilePicker) {
    window.showOpenFilePicker = function (opts) {
      opts = opts || {};
      var mimes = [];
      var types = opts.types || [];
      for (var i = 0; i < types.length; i++) {
        var acc = types[i].accept || {};
        for (var k in acc) { if (Object.prototype.hasOwnProperty.call(acc, k)) { mimes.push(k); } }
      }
      return pickNative(mimes.length ? mimes.join(',') : '*/*', !!opts.multiple).then(function (files) {
        var out = [];
        for (var j = 0; j < files.length; j++) { out.push(makeHandle(files[j])); }
        return out;
      });
    };
  }

  if (!window.showDirectoryPicker) {
    window.showDirectoryPicker = function () {
      return Promise.reject(new Error('当前环境不支持选择文件夹（showDirectoryPicker）'));
    };
  }

  /* ------------------------------------------------------------------ *
   * 4. Form restore on reload (browser-like behaviour)
   * ------------------------------------------------------------------ */

  var FORM_PREFIX = '__ht2a_form:';
  function isReload() {
    try {
      var nav = performance.getEntriesByType && performance.getEntriesByType('navigation')[0];
      if (nav && nav.type) { return nav.type === 'reload' || nav.type === 'back_forward'; }
    } catch (e) {}
    try { return performance.navigation && performance.navigation.type === 1; } catch (e) {}
    return false;
  }
  function controlKey(el, seen) {
    if (el.id) { return '#' + el.id; }
    var name = el.getAttribute('name');
    var base = name ? '@' + name : (el.tagName.toLowerCase() + ':' + (el.type || ''));
    var n = seen[base] = (seen[base] || 0) + 1;
    return base + '#' + n;
  }
  function snapshotForm() {
    var out = {}, seen = {};
    try {
      var els = document.querySelectorAll('input,textarea,select');
      for (var i = 0; i < els.length; i++) {
        var el = els[i], t = (el.type || '').toLowerCase();
        if (t === 'file' || t === 'password' || t === 'hidden' || t === 'submit' || t === 'button' || t === 'reset' || t === 'image') { continue; }
        var key = controlKey(el, seen);
        if (t === 'checkbox' || t === 'radio') {
          out[key] = { t: t, c: !!el.checked };
        } else if (el.tagName.toLowerCase() === 'select') {
          var vals = [];
          for (var j = 0; j < el.options.length; j++) { if (el.options[j].selected) { vals.push(j); } }
          out[key] = { t: 'select', v: vals };
        } else {
          out[key] = { t: 'text', v: el.value };
        }
      }
    } catch (e) {}
    return out;
  }
  function restoreForm() {
    var data = null;
    try { data = store.get('session', FORM_PREFIX + location.pathname); } catch (e) {}
    if (!data) { return; }
    var obj;
    try { obj = JSON.parse(data); } catch (e) { return; }
    var seen = {};
    try {
      var els = document.querySelectorAll('input,textarea,select');
      for (var i = 0; i < els.length; i++) {
        var el = els[i], t = (el.type || '').toLowerCase();
        var key = controlKey(el, seen);
        var d = obj[key];
        if (!d) { continue; }
        if (d.t === 'checkbox' || d.t === 'radio') {
          if (!!el.checked !== !!d.c) { el.checked = !!d.c; fire(el, 'change'); }
        } else if (d.t === 'select') {
          var vals = d.v || [];
          for (var j = 0; j < el.options.length; j++) { el.options[j].selected = vals.indexOf(j) >= 0; }
          fire(el, 'change');
        } else if (typeof d.v === 'string' && el.value !== d.v) {
          el.value = d.v;
          fire(el, 'input');
          fire(el, 'change');
        }
      }
    } catch (e) {}
  }
  function fire(el, type) {
    try {
      var ev;
      if (typeof Event === 'function') { ev = new Event(type, { bubbles: true }); }
      else { ev = document.createEvent('HTMLEvents'); ev.initEvent(type, true, false); }
      el.dispatchEvent(ev);
    } catch (e) {}
  }
  var formTimer = 0;
  function flushForm() {
    if (formTimer) {
      clearTimeout(formTimer);
      formTimer = 0;
    }
    try {
      var json = JSON.stringify(snapshotForm());
      if (json.length < 2 * 1024 * 1024) {
        store.set('session', FORM_PREFIX + location.pathname, json);
      }
    } catch (e) {}
  }
  function scheduleFormSave() {
    if (formTimer) { return; }
    formTimer = setTimeout(function () {
      formTimer = 0;
      try {
        var json = JSON.stringify(snapshotForm());
        if (json.length < 2 * 1024 * 1024) {
          store.set('session', FORM_PREFIX + location.pathname, json);
        }
      } catch (e) {}
    }, 400);
  }
  document.addEventListener('input', scheduleFormSave, true);
  document.addEventListener('change', scheduleFormSave, true);
  window.addEventListener('pagehide', flushForm, false);
  document.addEventListener('visibilitychange', function () {
    try {
      if (document.visibilityState === 'hidden') { flushForm(); }
    } catch (e) {}
  }, false);
  if (isReload()) {
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', function () { setTimeout(restoreForm, 50); });
    } else {
      setTimeout(restoreForm, 50);
    }
  }

  /* ------------------------------------------------------------------ *
   * 5. Error banner / blank page detection / diagnostics
   * ------------------------------------------------------------------ */

  function banner(text, title, sticky) {
    try {
      var old = document.getElementById('__ht2a_banner');
      if (old && old.parentNode) { old.parentNode.removeChild(old); }
      var box = document.createElement('div');
      box.id = '__ht2a_banner';
      box.setAttribute('style',
        'position:fixed;left:0;right:0;top:0;z-index:2147483647;' +
        'background:#7f1d1d;color:#fff;font:12px/1.45 monospace;' +
        'padding:10px 40px 10px 12px;max-height:45vh;overflow:auto;' +
        'box-shadow:0 3px 12px rgba(0,0,0,.45);white-space:pre-wrap;word-break:break-word;');
      var head = document.createElement('div');
      head.textContent = (title || '页面脚本错误') + (sticky ? '' : '（点 × 关闭）');
      head.setAttribute('style', 'font-weight:bold;margin-bottom:4px;color:#fecaca;');
      var body = document.createElement('div');
      body.textContent = text;
      var close = document.createElement('button');
      close.textContent = '×';
      close.setAttribute('style',
        'position:absolute;right:6px;top:4px;width:28px;height:28px;' +
        'background:transparent;border:0;color:#fff;font-size:20px;line-height:1;');
      close.onclick = function () { if (box.parentNode) { box.parentNode.removeChild(box); } };
      box.appendChild(head);
      box.appendChild(body);
      box.appendChild(close);
      (document.body || document.documentElement).appendChild(box);
    } catch (e) {}
  }

  function diagnostics() {
    var data = {
      shim: '1.2.0', url: location.href, title: document.title,
      info: INFO, ua: navigator.userAgent,
      viewport: [window.innerWidth, window.innerHeight],
      errors: errors.slice(-20),
      storage: []
    };
    try {
      var ks = store.keys('local');
      for (var i = 0; i < ks.length; i++) {
        var v = store.get('local', ks[i]);
        data.storage.push([ks[i], v === null ? null : v.length]);
      }
    } catch (e) {}
    return data;
  }
  window.HT2ADiagnostics = diagnostics;

  function onPageError(msg) {
    addError(msg);
    if (DEBUG) {
      banner(str(msg), '页面脚本错误');
    }
  }
  window.addEventListener('error', function (ev) {
    try {
      var t = ev.target;
      if (t && t !== window && t.tagName) {
        onPageError('资源加载失败: <' + t.tagName.toLowerCase() + '> ' + (t.src || t.href || ''));
        return;
      }
      onPageError(str(ev.message) + '\n' + str(ev.filename) + ':' + str(ev.lineno) + ':' + str(ev.colno));
    } catch (e) {}
  }, true);
  window.addEventListener('unhandledrejection', function (ev) {
    try {
      var r = ev.reason;
      onPageError('未处理的 Promise 拒绝: ' + (r && r.stack ? r.stack : str(r)));
    } catch (e) {}
  }, false);

  function checkBlank() {
    try {
      if (document.__ht2aBlankShown) { return; }
      var b = document.body;
      if (!b) { return; }
      var txt = (b.innerText || b.textContent || '').replace(/\s+/g, '');
      var media = b.querySelector('canvas,svg,video,iframe,img,object,embed');
      var visible = false;
      if (media) {
        var r = media.getBoundingClientRect();
        visible = r.width > 2 && r.height > 2;
      }
      if (!txt && !visible) {
        document.__ht2aBlankShown = true;
        banner('页面没有可见内容。可能是脚本错误、资源缺失或页面依赖网络。\n\n' +
               '最近错误：\n' + (errors.length ? errors.slice(-3).map(function (x) { return x.m; }).join('\n') : '（无）'),
               '空白页面', true);
      }
    } catch (e) {}
  }
  window.addEventListener('load', function () { setTimeout(checkBlank, 1800); }, false);
  if (document.readyState === 'complete') { setTimeout(checkBlank, 1800); }

  /* ------------------------------------------------------------------ *
   * 6. Public helpers
   * ------------------------------------------------------------------ */

  // Copy diagnostics from the error banner's long-press? Expose a helper.
  window.HT2ACopyDiagnostics = function () {
    try { B.copy(JSON.stringify(diagnostics(), null, 2)); } catch (e) {}
  };

  window.HT2A_WEB = {
    info: INFO,
    debug: DEBUG,
    errors: errors,
    diagnostics: diagnostics,
    copyDiagnostics: window.HT2ACopyDiagnostics,
    saveBlob: function (blob, name) { return saveBlob(blob, name || 'download'); },
    saveText: function (name, text, mime) {
      mime = mime || 'text/plain;charset=utf-8';
      return saveBlob(new Blob([str(text)], { type: mime }), name || 'download.txt', mime);
    },
    saveJSON: function (name, obj, pretty) {
      var text = typeof obj === 'string' ? obj : JSON.stringify(obj, null, pretty ? 2 : 0);
      var n = name || 'data.json';
      if (n.indexOf('.') < 0) { n += '.json'; }
      return saveBlob(new Blob([text], { type: 'application/json' }), n, 'application/json');
    },
    storage: publicStorage,
    pickFiles: pickNative
  };
  log('info', 'HT2A shim 1.2 ready' + (DEBUG ? ' (debug)' : ''));
})();
