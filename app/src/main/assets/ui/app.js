/* ============================================================
   HTML2APK · 工业复古控制台 UI 逻辑（v1.2）
   原生桥：window.html2apk.*
     pickHtmlFile(cb) / pickFolder(cb) / pickIcon(cb)
     startBuild(configJson) / installApk() / openApkFolder()
     getEngineStatus() -> JSON 字符串
     getDownloadDir() -> 输出目录 / toast(msg) / copyText(text)
   原生回调：
     onPickResult(path, metaJson) / onIconResult(path, preview)
     onBuildProgress(step, msg) / onBuildDone(resultJson)
     onBuildError(message, detailJson)
   兼容旧回调：onBuildDone(path, size) / onBuildError(message)
   ============================================================ */
(function () {
  'use strict';

  var LOG_RENDER_MAX = 200000; // 失败详情最多渲染的日志字符数（保留末尾）

  var state = {
    inputDir: '',
    inputKind: '',
    htmlFiles: [],
    entryFile: 'index.html',
    debug: false,
    orientation: 'auto',
    iconPath: '',
    building: false,
    engineReady: false,
    checking: false,
    lastBuildPkg: '',
    lastOutput: '',
    lastErrorLog: '',
    lastErrorLogPath: '',
    pollTimer: null
  };

  var outputDir = '';
  var $ = function (id) { return document.getElementById(id); };
  var LOG = $('log');

  /* ---------- 基础工具 ---------- */
  function api() {
    try { return window.html2apk || null; } catch (e) { return null; }
  }

  function str(v) { return v == null ? '' : String(v); }

  function parseJsonArg(raw) {
    if (raw == null) return null;
    if (typeof raw === 'object') return raw;
    if (typeof raw !== 'string') return null;
    var s = raw.trim();
    if (!s) return null;
    try { return JSON.parse(s); } catch (e) { return null; }
  }

  function cleanLog(text) {
    return str(text)
      .replace(/\u001b\[[0-9;]*[A-Za-z]/g, '')
      .replace(/\r\n?/g, '\n');
  }

  function truncateLog(text) {
    var s = cleanLog(text);
    if (s.length <= LOG_RENDER_MAX) return s;
    return s.slice(-LOG_RENDER_MAX) +
      '\n\n[已截断：只显示最后 ' + LOG_RENDER_MAX + ' 字符 / TRUNCATED TO LAST 200K CHARS]';
  }

  function wasTruncated(text) {
    return cleanLog(text).length > LOG_RENDER_MAX;
  }

  function normRel(p) {
    var s = str(p).replace(/\\/g, '/').replace(/\/{2,}/g, '/').trim();
    s = s.replace(/^\.\//, '');
    s = s.replace(/^\/+/, '');
    return s;
  }

  function baseName(p) {
    var s = str(p).replace(/\\/g, '/').replace(/\/+$/, '');
    var i = s.lastIndexOf('/');
    var b = i >= 0 ? s.slice(i + 1) : s;
    return b || s || str(p);
  }

  function inferKind(path) {
    return /\.html?$/i.test(str(path)) ? 'file' : 'folder';
  }

  function pickEntry(files, preferred) {
    if (preferred && files.indexOf(preferred) >= 0) return preferred;
    for (var i = 0; i < files.length; i++) {
      if (/(^|\/)index\.html?$/i.test(files[i])) return files[i];
    }
    return files.length ? files[0] : '';
  }

  function looksLikeSize(v) {
    return typeof v === 'string' && /^\s*\d+(?:\.\d+)?\s*(?:B|KB|MB|GB|TB)\s*$/i.test(v);
  }

  function fmtSize(v) {
    var s = str(v).trim();
    return s || '-';
  }

  function fmtDuration(ms) {
    if (ms == null || ms === '') return '-';
    var n = Number(ms);
    if (!isFinite(n) || n < 0) return '-';
    if (n < 1000) return Math.round(n) + ' ms';
    var sec = n / 1000;
    return String(Math.round(sec * 10) / 10) + ' s';
  }

  function setText(id, text) {
    var el = $(id);
    if (el) el.textContent = str(text);
    return el;
  }

  function joinWarning(a, b) {
    return a ? (a + '；' + b) : b;
  }

  function getLog() {
    if (!LOG) LOG = $('log');
    return LOG;
  }

  function toast(msg) {
    var a = api();
    if (a && typeof a.toast === 'function') {
      try { a.toast(str(msg)); return; } catch (e) { /* 回退到页面内 toast */ }
    }
    var t = $('toast');
    if (!t) return;
    t.textContent = str(msg);
    t.classList.add('show');
    clearTimeout(t._h);
    t._h = setTimeout(function () { t.classList.remove('show'); }, 1800);
  }

  /** 优先走原生剪贴板；不可用时回退到 execCommand（浏览器预览） */
  function copyText(text, okMsg) {
    var s = str(text);
    if (!s) { toast('没有可复制的内容'); return false; }
    var a = api();
    if (a && typeof a.copyText === 'function') {
      try {
        a.copyText(s);
        toast(okMsg || '已复制到剪贴板');
        return true;
      } catch (e) { /* 继续尝试前端回退 */ }
    }
    var ok = false;
    try {
      var ta = document.createElement('textarea');
      ta.value = s;
      ta.setAttribute('readonly', 'readonly');
      ta.style.position = 'fixed';
      ta.style.top = '-1000px';
      ta.style.left = '0';
      ta.style.opacity = '0';
      document.body.appendChild(ta);
      ta.focus();
      ta.select();
      try { ta.setSelectionRange(0, s.length); } catch (e2) { /* ignore */ }
      ok = document.execCommand('copy');
      document.body.removeChild(ta);
    } catch (e3) {
      ok = false;
    }
    toast(ok ? (okMsg || '已复制到剪贴板') : '复制失败，请手动选择文本');
    return ok;
  }

  /* ---------- 引擎状态 ---------- */
  function setEngine(status, text) {
    var led = $('engLed');
    if (led) led.className = 'led ' + status;
    var txt = $('engText');
    if (txt) txt.textContent = str(text);
  }

  function showEngineFault(title, hint) {
    state.engineReady = false;
    setEngine('fault', 'ENGINE FAULT');
    var errEl = $('engineErr');
    var hintEl = $('engineHint');
    if (errEl) errEl.textContent = str(title) || '构建引擎未就绪';
    if (hintEl) {
      var h = str(hint).trim();
      hintEl.textContent = h;
      hintEl.hidden = !h;
    }
    var panel = $('enginePanel');
    if (panel) panel.hidden = false;
  }

  function hideEngineFault() {
    var panel = $('enginePanel');
    if (panel) panel.hidden = true;
  }

  function stopEnginePoll() {
    if (state.pollTimer) {
      clearInterval(state.pollTimer);
      state.pollTimer = null;
    }
  }

  function checkEngine() {
    if (state.checking) return;
    state.checking = true;
    try {
      var a = api();
      if (!a || typeof a.getEngineStatus !== 'function') {
        showEngineFault(
          '未连接原生桥 · NATIVE BRIDGE MISSING',
          '请在 HTML2APK App 内打开此界面；浏览器预览无法构建 APK。'
        );
        return;
      }

      var raw = null;
      try {
        raw = a.getEngineStatus();
      } catch (e) {
        showEngineFault(
          '引擎状态读取失败 · STATUS READ FAILED',
          '调用 getEngineStatus 失败：' + (e && e.message ? e.message : str(e))
        );
        return;
      }

      var res = parseJsonArg(raw);
      if (!res || typeof res !== 'object' || Array.isArray(res)) {
        showEngineFault(
          '引擎状态无法解析 · INVALID STATUS',
          '原生返回的内容不是有效 JSON，请重新检测或重启应用。'
        );
        return;
      }

      var ready = (res.ready === true || res.ready === 1 || res.ready === 'true');
      if (ready) {
        state.engineReady = true;
        setEngine('on', 'ENGINE READY');
        var led = $('engLed');
        if (led) {
          var ver = str(res.aapt2Version).trim();
          led.title = ver ? ('aapt2 ' + ver) : 'engine ready';
        }
        hideEngineFault();
        initOutputDir();
        stopEnginePoll();
      } else {
        var err = str(res.error).trim();
        var hint = str(res.hint).trim();
        showEngineFault(
          err || '构建引擎未就绪 · ENGINE NOT READY',
          hint || '请确认 App 内置的 aapt2 / android.jar 完整，或重新安装应用后再试。'
        );
      }
    } finally {
      state.checking = false;
      var btn = $('recheckBtn');
      if (btn) btn.disabled = false; // 任何情况下都可再次检测
      refreshBuildBtn();
    }
  }

  function initOutputDir() {
    if (outputDir) return;
    var a = api();
    if (!a || typeof a.getDownloadDir !== 'function') return;
    var d = '';
    try { d = str(a.getDownloadDir()).trim(); } catch (e) { d = ''; }
    if (!d) return;
    outputDir = d;
    var p = $('outDirPath');
    var btn = $('outDirHint');
    if (p) { p.textContent = d; p.title = d; }
    if (btn) { btn.hidden = false; btn.title = d; }
  }

  function startEnginePoll() {
    if (state.pollTimer) return;
    var tries = 0;
    state.pollTimer = setInterval(function () {
      if (state.engineReady || tries >= 12) {
        stopEnginePoll();
        return;
      }
      tries += 1;
      checkEngine();
    }, 1500);
  }

  /* ---------- 输入选择 ---------- */
  function renderEntrySelector(files, preferred, show) {
    var sel = $('entrySelect');
    var field = $('entryField');
    var list = files || [];
    if (sel) {
      sel.innerHTML = '';
      list.forEach(function (f) {
        var opt = document.createElement('option');
        opt.value = f;
        opt.textContent = f;
        sel.appendChild(opt);
      });
      if (show && list.length && preferred) sel.value = preferred;
    }
    if (field) field.hidden = !(show && list.length > 0);
  }

  function renderWarnings(meta) {
    var box = $('warnList');
    if (!box) return;
    var list = [];

    if (state.inputKind === 'file') {
      list.push('单文件模式：只打包当前这一个 HTML，同目录下的 CSS / JS / 图片不会被包含；完整站点请选择文件夹。');
    }
    if (meta && Array.isArray(meta.warnings)) {
      meta.warnings.forEach(function (w) {
        var s = str(w).trim();
        if (s && list.indexOf(s) < 0) list.push(s);
      });
    } else if (meta && typeof meta.warnings === 'string' && meta.warnings.trim()) {
      list.push(meta.warnings.trim());
    }
    if (state.inputKind === 'folder' && state.htmlFiles.length === 0) {
      list.push('未在该目录中发现 HTML 文件，请确认选择的是网页目录。');
    }

    box.innerHTML = '';
    if (!list.length) { box.hidden = true; return; }

    var head = document.createElement('div');
    head.className = 'warn-head';
    head.textContent = '输入提示 INPUT NOTES · ' + list.length;
    box.appendChild(head);

    list.forEach(function (text) {
      var item = document.createElement('div');
      item.className = 'warn-item';
      var mark = document.createElement('span');
      mark.className = 'wi';
      mark.setAttribute('aria-hidden', 'true');
      mark.textContent = '!';
      var body = document.createElement('span');
      body.className = 'wt';
      body.textContent = text;
      item.appendChild(mark);
      item.appendChild(body);
      box.appendChild(item);
    });
    box.hidden = false;
  }

  function applyPickResult(path, meta) {
    var kind = meta.kind === 'file' ? 'file' : (meta.kind === 'folder' ? 'folder' : inferKind(path));
    state.inputDir = path;
    state.inputKind = kind;

    var files = [];
    var seen = {};
    if (Array.isArray(meta.htmlFiles)) {
      meta.htmlFiles.forEach(function (f) {
        var n = normRel(f);
        if (!n || seen[n]) return;
        seen[n] = 1;
        files.push(n);
      });
    }
    state.htmlFiles = files;

    var preferred = normRel(meta.defaultEntry);
    var entry;
    if (kind === 'file') {
      entry = (preferred && (!files.length || files.indexOf(preferred) >= 0))
        ? preferred
        : (files.length > 1 ? pickEntry(files, '') : '');
    } else {
      entry = pickEntry(files, preferred) || preferred;
    }
    state.entryFile = entry || 'index.html';

    renderEntrySelector(files, state.entryFile, files.length > 1);
    renderWarnings(meta);

    var name = baseName(path);
    var pt = $('pathText');
    if (pt) {
      pt.textContent = name || path;
      pt.title = path;
    }
    var box = $('pathBox');
    if (box) box.hidden = false;
  }

  function clearInput() {
    state.inputDir = '';
    state.inputKind = '';
    state.htmlFiles = [];
    state.entryFile = 'index.html';

    var box = $('pathBox');
    if (box) box.hidden = true;
    var pt = $('pathText');
    if (pt) { pt.textContent = ''; pt.removeAttribute('title'); }

    renderEntrySelector([], '', false);
    var warnings = $('warnList');
    if (warnings) { warnings.innerHTML = ''; warnings.hidden = true; }
    refreshBuildBtn();
  }

  function autoFillPackage() {
    var pkgInput = $('pkg');
    var nameInput = $('appName');
    if (!pkgInput || !nameInput) return;
    if (pkgInput.value.trim()) return;
    var name = nameInput.value.trim();
    if (!name) return;
    var slug = name.toLowerCase().replace(/[^a-z0-9]+/g, '.').replace(/^\.+|\.+$/g, '');
    if (!slug) return;
    if (/^[0-9]/.test(slug)) slug = 'app.' + slug;
    pkgInput.value = 'com.' + slug;
  }

  function refreshBuildBtn() {
    var btn = $('buildBtn');
    if (!btn) return;
    var nameEl = $('appName');
    var canBuild = state.engineReady && !!state.inputDir && !state.building &&
      !!nameEl && !!nameEl.value.trim();
    btn.disabled = !canBuild;
  }

  /* ---------- 日志 ---------- */
  function appendLog(text, cls) {
    var log = getLog();
    if (!log) return;
    var span = document.createElement('span');
    if (cls) span.className = cls;
    span.textContent = str(text) + '\n';
    log.appendChild(span);
    log.scrollTop = log.scrollHeight;
  }

  function stamp() {
    var d = new Date();
    var p = function (n) { return ('0' + n).slice(-2); };
    return '[' + p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds()) + '] ';
  }

  function finishBuildUI() {
    state.building = false;
    var btn = $('buildBtn');
    if (btn) btn.textContent = 'EXECUTE BUILD';
    refreshBuildBtn();
  }

  function hideFailPanel() {
    var panel = $('failPanel');
    if (panel) panel.hidden = true;
    var detailsEl = $('failDetails');
    if (detailsEl) detailsEl.open = false;
  }

  function scrollToPanel(panel) {
    if (!panel) return;
    try {
      panel.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    } catch (e) {
      try { panel.scrollIntoView(); } catch (e2) { /* ignore */ }
    }
  }

  /* ---------- 构建 ---------- */
  function startBuild() {
    if (state.building) return;
    var a = api();
    if (!a || typeof a.startBuild !== 'function') { toast('未连接原生桥，无法构建'); return; }
    if (!state.engineReady) { toast('构建引擎未就绪，请先点「重新检测」'); return; }
    if (!state.inputDir) { toast('请先选择 HTML 文件或文件夹'); return; }

    var nameEl = $('appName');
    var appName = nameEl ? nameEl.value.trim() : '';
    if (!appName) { toast('请填写应用名称'); if (nameEl) nameEl.focus(); return; }

    var pkgEl = $('pkg');
    var pkg = pkgEl ? pkgEl.value.trim() : '';
    var pkgPattern = /^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$/;
    if (pkg && !pkgPattern.test(pkg)) { toast('包名格式不正确，例如 com.example.app'); return; }
    if (!pkg) {
      pkg = 'com.html2apk.app' + (Date.now() % 100000);
      if (pkgEl) pkgEl.value = pkg; // 回填，结果面板与下次构建都用同一个包名
    }

    var verNameEl = $('verName');
    var verCodeEl = $('verCode');
    var versionName = (verNameEl && verNameEl.value.trim()) || '1.0';
    var versionCode = parseInt(verCodeEl ? verCodeEl.value : '1', 10);
    if (!versionCode || versionCode < 1) versionCode = 1;

    var entryFile = state.entryFile || 'index.html';
    var debug = state.debug ? '1' : '0';
    var orientation = state.orientation || 'auto';

    var cfg = {
      appName: appName,
      packageName: pkg,
      versionName: versionName,
      versionCode: versionCode,
      entryFile: entryFile,
      debug: debug,
      orientation: orientation,
      inputDir: state.inputDir,
      iconPath: state.iconPath || '',
      statusBarColor: '#f0ede4',
      navBarColor: '#f0ede4',
      backgroundColor: '#f0ede4'
    };

    state.building = true;
    state.lastBuildPkg = pkg;
    state.lastOutput = '';
    state.lastErrorLog = '';
    state.lastErrorLogPath = '';

    hideFailPanel();
    var resultPanel = $('resultPanel');
    if (resultPanel) resultPanel.hidden = true;

    var log = getLog();
    if (log) log.textContent = '';

    var btn = $('buildBtn');
    if (btn) { btn.textContent = 'BUILDING…'; btn.disabled = true; }
    setEngine('busy', 'ENGINE BUSY');

    appendLog(stamp() + '$ html2apk build --app "' + appName + '" --pkg ' + pkg);
    appendLog(stamp() + '  entry=' + entryFile + '  debug=' + debug + '  orientation=' + orientation, 't');
    refreshBuildBtn();

    try {
      a.startBuild(JSON.stringify(cfg));
    } catch (e) {
      renderBuildError('调用原生构建失败 · BRIDGE ERROR', {
        hint: '请确认 App 为最新版本，然后重试。',
        log: 'startBuild() threw:\n' + (e && e.stack ? e.stack : str(e)),
        logPath: ''
      });
    }
  }

  /* ---------- 构建回调 ---------- */
  function normalizeBuildResult(a, b) {
    var r = parseJsonArg(a);
    if (r && typeof r === 'object' && !Array.isArray(r)) return r;

    var raw = str(a).trim();
    if (raw === 'null' || raw === 'undefined') raw = '';
    if (raw.charAt(0) === '{' || raw.charAt(0) === '[') {
      return { _malformed: true, sizeLabel: looksLikeSize(b) ? b : '' };
    }
    return {
      apkPath: raw,
      sizeLabel: looksLikeSize(b) ? b : (str(b).trim() || '')
    };
  }

  function normalizeErrorDetail(raw) {
    var d = parseJsonArg(raw);
    if (d && typeof d === 'object' && !Array.isArray(d)) {
      return {
        hint: str(d.hint).trim(),
        log: str(d.log),
        logPath: str(d.logPath).trim()
      };
    }
    if (typeof raw === 'string' && raw.trim()) {
      // 非 JSON 的第二参数：按纯日志处理，避免丢失信息
      return { hint: '', log: raw, logPath: '' };
    }
    return { hint: '', log: '', logPath: '' };
  }

  function renderBuildError(message, detail) {
    var msg = str(message).trim() || '构建失败 · BUILD FAILED';
    var d = detail || { hint: '', log: '', logPath: '' };

    finishBuildUI();
    setEngine('fault', 'BUILD FAILED');

    var fallback = msg;
    if (d.hint) fallback += '\n提示：' + d.hint;
    if (d.logPath) fallback += '\n日志文件：' + d.logPath;
    var fullLog = cleanLog(d.log) || fallback;

    state.lastErrorLog = fullLog;
    state.lastErrorLogPath = d.logPath || '';
    state.lastOutput = '';

    appendLog(stamp() + 'ERROR: ' + msg, 'err');
    if (d.hint) appendLog(stamp() + 'HINT: ' + d.hint, 'warn');
    if (d.logPath) appendLog(stamp() + 'LOG: ' + d.logPath, 't');

    setText('failMsg', msg);

    var hintEl = $('failHint');
    if (hintEl) {
      hintEl.textContent = d.hint || '';
      hintEl.hidden = !d.hint;
    }

    var pathHint = $('failLogPathHint');
    var pathEl = $('failLogPath');
    if (pathHint && pathEl) {
      if (d.logPath) {
        pathEl.textContent = d.logPath;
        pathEl.title = d.logPath;
        pathHint.hidden = false;
      } else {
        pathHint.hidden = true;
      }
    }

    var metaEl = $('failLogMeta');
    if (metaEl) metaEl.textContent = wasTruncated(fullLog) ? 'TAIL 200K' : 'FULL LOG';

    var pre = $('failLog');
    if (pre) pre.textContent = truncateLog(fullLog);

    var detailsEl = $('failDetails');
    if (detailsEl) detailsEl.open = false;

    var resultPanel = $('resultPanel');
    if (resultPanel) resultPanel.hidden = true;

    var panel = $('failPanel');
    if (panel) {
      panel.hidden = false;
      scrollToPanel(panel);
    }
    toast('构建失败');
  }

  function renderBuildDone(result) {
    var r = result || {};
    finishBuildUI();
    hideFailPanel();
    hideEngineFault();
    state.engineReady = true;
    stopEnginePoll();
    setEngine('on', 'ENGINE READY');

    var apkPath = str(r.apkPath || r.path || r.filePath);
    var publicPath = str(r.publicPath);
    if (!publicPath) publicPath = apkPath;
    var size = fmtSize(r.sizeLabel || r.size);
    var duration = fmtDuration(r.durationMs != null ? r.durationMs : r.duration);
    var pkgEl = $('pkg');
    var pkg = str(r.packageName) || state.lastBuildPkg || (pkgEl ? pkgEl.value.trim() : '');
    var logPath = str(r.logPath).trim();

    var warning = '';
    if (Array.isArray(r.warnings)) {
      warning = r.warnings.map(str).filter(Boolean).join('；');
    } else {
      warning = str(r.warning);
    }
    if (r._malformed) {
      warning = joinWarning(warning, '构建结果数据不完整，无法解析输出路径；可打开输出目录查看。');
    }
    if (!apkPath && !publicPath) {
      warning = joinWarning(warning, '原生未返回 APK 路径，可打开输出目录查看。');
    }

    state.lastOutput = publicPath || apkPath;

    var line = stamp() + 'BUILD COMPLETE';
    if (size !== '-') line += ' - ' + size;
    if (duration !== '-') line += ' · ' + duration;
    appendLog(line, 'ok');
    if (publicPath) appendLog(stamp() + 'OUTPUT: ' + publicPath, 't');
    if (warning) appendLog(stamp() + 'WARN: ' + warning, 'warn');

    var rPath = setText('rPath', publicPath || '-');
    if (rPath) rPath.title = apkPath || publicPath || '';
    setText('rSize', size);
    setText('rPkg', pkg || '-');

    var rDuration = setText('rDuration', duration);
    if (rDuration) {
      rDuration.title = (r.durationMs != null) ? ('durationMs=' + r.durationMs) : '';
    }

    var logRow = $('rLogRow');
    var logEl = $('rLog');
    if (logRow && logEl) {
      if (logPath) {
        logEl.textContent = logPath;
        logEl.title = logPath;
        logRow.hidden = false;
      } else {
        logEl.textContent = '-';
        logEl.removeAttribute('title');
        logRow.hidden = true;
      }
    }

    var warnEl = $('rWarning');
    if (warnEl) {
      warnEl.textContent = warning ? ('⚠ ' + warning) : '';
      warnEl.hidden = !warning;
    }

    var panel = $('resultPanel');
    if (panel) {
      panel.hidden = false;
      scrollToPanel(panel);
    }
    refreshBuildBtn();
    toast('构建完成');
  }

  /* ---------- 原生回调（先挂载，避免桥提前调用） ---------- */
  window.onPickResult = function (path, metaJson) {
    var p = str(path);
    if (!p) return; // 空路径 = 用户取消，保留上一次选择
    var meta = parseJsonArg(metaJson);
    if (!meta || typeof meta !== 'object' || Array.isArray(meta)) meta = {};
    applyPickResult(p, meta);
    autoFillPackage();
    refreshBuildBtn();
  };

  window.onIconResult = function (path, preview) {
    var p = str(path);
    if (!p) { toast('未选择图片'); return; }
    state.iconPath = p;
    var img = $('iconPreview');
    var pv = str(preview);
    if (img) {
      if (pv) {
        img.src = pv;
        img.hidden = false;
      } else {
        img.hidden = true;
        img.removeAttribute('src');
      }
    }
    var clearBtn = $('iconClear');
    if (clearBtn) clearBtn.hidden = false;
    toast(pv ? '封面图标已设置' : '封面图标已设置（无预览）');
  };

  window.onBuildProgress = function (step, msg) {
    var s = str(step).trim();
    var m = str(msg).trim();
    appendLog(stamp() + (s ? '[' + s + '] ' : '') + m, 'step');
  };

  window.onBuildDone = function (resultJson, maybeSize) {
    renderBuildDone(normalizeBuildResult(resultJson, maybeSize));
  };

  window.onBuildError = function (message, detailJson) {
    renderBuildError(str(message).trim() || '构建失败 · BUILD FAILED', normalizeErrorDetail(detailJson));
  };

  /* ---------- 事件绑定 ---------- */
  function on(id, ev, fn) {
    var el = $(id);
    if (el) el.addEventListener(ev, fn);
  }

  function bind() {
    on('pickFile', 'click', function () {
      if (state.building) { toast('正在构建，请稍候'); return; }
      var a = api();
      if (!a || typeof a.pickHtmlFile !== 'function') { toast('未连接原生桥，无法选择文件'); return; }
      try { a.pickHtmlFile('onPickResult'); } catch (e) { toast('打开文件选择器失败'); }
    });

    on('pickDir', 'click', function () {
      if (state.building) { toast('正在构建，请稍候'); return; }
      var a = api();
      if (!a || typeof a.pickFolder !== 'function') { toast('未连接原生桥，无法选择文件夹'); return; }
      try { a.pickFolder('onPickResult'); } catch (e) { toast('打开文件夹选择器失败'); }
    });

    on('clearPath', 'click', clearInput);

    on('iconBtn', 'click', function () {
      if (state.building) { toast('正在构建，请稍候'); return; }
      var a = api();
      if (!a || typeof a.pickIcon !== 'function') { toast('未连接原生桥，无法选择图片'); return; }
      try { a.pickIcon('onIconResult'); } catch (e) { toast('打开图片选择器失败'); }
    });

    on('iconClear', 'click', function () {
      state.iconPath = '';
      var img = $('iconPreview');
      if (img) { img.hidden = true; img.removeAttribute('src'); }
      var clearBtn = $('iconClear');
      if (clearBtn) clearBtn.hidden = true;
    });

    on('buildBtn', 'click', startBuild);

    on('installBtn', 'click', function () {
      var a = api();
      if (!a || typeof a.installApk !== 'function') { toast('未连接原生桥'); return; }
      try { a.installApk(); } catch (e) { toast('安装调用失败'); }
    });

    on('openDirBtn', 'click', function () {
      var a = api();
      if (!a || typeof a.openApkFolder !== 'function') { toast('未连接原生桥'); return; }
      try { a.openApkFolder(); } catch (e) { toast('打开目录失败'); }
    });

    on('copyPathBtn', 'click', function () {
      copyText(state.lastOutput, 'APK 路径已复制');
    });

    on('copyLogBtn', 'click', function () {
      var text = state.lastErrorLog;
      if (!text) {
        var pre = $('failLog');
        text = pre ? pre.textContent : '';
      }
      copyText(text, '日志已复制');
    });

    on('retryBtn', 'click', function () {
      if (state.building) return;
      startBuild();
    });

    on('recheckBtn', 'click', function () {
      setEngine('busy', 'CHECKING');
      checkEngine();
    });

    on('outDirHint', 'click', function () {
      copyText(outputDir, '输出目录已复制');
    });

    on('entrySelect', 'change', function () {
      var sel = $('entrySelect');
      state.entryFile = (sel && sel.value) ? sel.value : 'index.html';
    });

    var debugEl = $('debugMode');
    if (debugEl) {
      state.debug = !!debugEl.checked;
      debugEl.addEventListener('change', function () { state.debug = !!debugEl.checked; });
    }

    var orientationEl = $('orientation');
    if (orientationEl) {
      state.orientation = orientationEl.value || 'auto';
      orientationEl.addEventListener('change', function () {
        state.orientation = orientationEl.value || 'auto';
      });
    }

    ['appName', 'pkg', 'verName', 'verCode'].forEach(function (id) {
      var el = $(id);
      if (el) el.addEventListener('input', refreshBuildBtn);
    });
    on('appName', 'input', autoFillPackage);
  }

  /* ---------- 启动 ---------- */
  function init() {
    bind();
    initOutputDir();
    checkEngine();
    if (!state.engineReady) startEnginePoll();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
