/* Injected by the launcher at the top of every app's index.html.
 * Makes egui/eframe web builds behave on a touch WebView:
 *   1. downloads   <a download href="blob:..."> -> native Save-as dialog (window.ArtcraftHost)
 *   2. scrolling   one-finger drag -> wheel events (+ momentum); press-and-hold then drag = normal pointer drag
 *   3. keyboard    show the soft keyboard for eframe's hidden text input; double-tap to edit slide text etc.
 * Every host call is optional, so the same file also runs in a plain desktop browser (used for testing). */
(function () {
  'use strict';
  if (window.__artcraftShim) return;
  window.__artcraftShim = true;

  function hostCall(name) {
    var h = window.ArtcraftHost;
    try {
      if (h && typeof h[name] === 'function') return h[name].apply(h, Array.prototype.slice.call(arguments, 1));
    } catch (e) { console.error('ArtcraftHost.' + name, e); }
    return undefined;
  }

  /* ------------------------------------------------------------------ 1. downloads */
  var blobs = new Map();
  var origCreate = URL.createObjectURL.bind(URL);
  var origRevoke = URL.revokeObjectURL.bind(URL);
  URL.createObjectURL = function (obj) {
    var u = origCreate(obj);
    if (obj instanceof Blob) blobs.set(u, obj);
    return u;
  };
  URL.revokeObjectURL = function (u) {
    setTimeout(function () { blobs.delete(u); }, 20000); // apps often revoke right after click()
    return origRevoke(u);
  };

  function isDownload(a) {
    return !!a && a.tagName === 'A' && a.hasAttribute('download') && /^(blob:|data:)/.test(a.href) &&
      typeof window.ArtcraftHost !== 'undefined';
  }
  function readB64(part) {
    return new Promise(function (resolve, reject) {
      var r = new FileReader();
      r.onload = function () { var s = String(r.result); resolve(s.substring(s.indexOf(',') + 1)); };
      r.onerror = function () { reject(r.error); };
      r.readAsDataURL(part);
    });
  }
  async function save(a) {
    var href = a.href;
    var name = a.getAttribute('download') || 'download';
    var blob = blobs.get(href) || await (await fetch(href)).blob();
    var token = hostCall('beginSave', name, blob.type || 'application/octet-stream');
    if (!token) { console.error('Save: host unavailable'); return; }
    try {
      var CH = 1 << 20;
      for (var off = 0; off < blob.size; off += CH) {
        if (!hostCall('writeChunk', token, await readB64(blob.slice(off, off + CH)))) throw new Error('write failed');
      }
      hostCall('finishSave', token);
    } catch (e) {
      hostCall('abortSave', token);
      console.error('Save failed', e);
    }
  }
  var origClick = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    if (isDownload(this)) { save(this); return; }
    return origClick.apply(this, arguments);
  };
  var origDispatch = HTMLAnchorElement.prototype.dispatchEvent;
  HTMLAnchorElement.prototype.dispatchEvent = function (ev) {
    if (ev && ev.type === 'click' && isDownload(this)) { save(this); return true; }
    return origDispatch.apply(this, arguments);
  };
  document.addEventListener('click', function (e) {
    var a = e.target && e.target.closest ? e.target.closest('a') : null;
    if (isDownload(a)) { e.preventDefault(); save(a); }
  }, true);

  /* ------------------------------------------------------------------ 3. keyboard (defined first: scrolling uses it) */
  var origFocus = HTMLElement.prototype.focus;
  var origBlur = HTMLElement.prototype.blur;
  var agent = null;            // eframe's hidden <input> ("text agent")
  var holdKeyboard = false;    // keep the agent focused even when the app says "no text field is active"
  var lastAppFocus = 0;        // last time the app itself focused an editable element
  var hideTimer = 0;

  function isEditable(el) {
    if (!el || !el.tagName) return false;
    if (el.tagName === 'TEXTAREA' || el.isContentEditable) return true;
    if (el.tagName !== 'INPUT') return false;
    return !/^(button|checkbox|radio|file|submit|reset|image|range|color)$/i.test(el.type || 'text');
  }
  function findAgent() {
    if (agent && agent.isConnected) return agent;
    var els = document.getElementsByTagName('input');
    for (var i = 0; i < els.length; i++) {
      var e = els[i];
      if (e.type === 'text' && e.style.width === '1px' && e.hasAttribute('autocapitalize')) { agent = e; return e; }
    }
    return null;
  }
  function showKb() { clearTimeout(hideTimer); hostCall('showKeyboard'); }
  function scheduleHide() {
    clearTimeout(hideTimer);
    hideTimer = setTimeout(function () {
      if (!holdKeyboard && !isEditable(document.activeElement)) hostCall('hideKeyboard');
    }, 250);
  }
  HTMLElement.prototype.focus = function () {
    // While the keyboard is held, the app re-focusing its canvas (it does so every frame while editing) must not
    // pull focus away from the text input, or the keyboard would close again.
    if (holdKeyboard && this.tagName === 'CANVAS') {
      var held = findAgent();
      if (held && document.activeElement === held) return;
    }
    var r = origFocus.apply(this, arguments);
    if (isEditable(this)) { lastAppFocus = Date.now(); showKb(); }
    return r;
  };
  HTMLElement.prototype.blur = function () {
    if (holdKeyboard && this === findAgent()) return; // eframe blurs the agent every frame it thinks nothing is being edited
    var r = origBlur.apply(this, arguments);
    if (isEditable(this)) scheduleHide();
    return r;
  };
  /* Called by the launcher (keyboard button, or when the system keyboard is dismissed). */
  window.__acKeyboard = function (on) {
    var a = findAgent();
    holdKeyboard = !!on;
    if (on) {
      if (a) origFocus.call(a, { preventScroll: true });
      showKb();
    } else {
      if (a && document.activeElement === a) origBlur.call(a);
      clearTimeout(hideTimer);
      hostCall('hideKeyboard');
    }
  };

  /* ------------------------------------------------------------------ 2. touch scrolling */
  var SLOP = 10;       // px of movement before a touch counts as a scroll
  var HOLD_MS = 350;   // press-and-hold this long and the touch becomes a normal pointer drag (select / move)
  var T = null;        // current gesture {mode: 'pending' | 'scroll' | 'pass', ...}
  var momentum = null;
  var lastTap = null;

  function scrollEnabled() { return hostCall('touchScroll') !== false; }
  function isCanvas(el) { return !!el && el.tagName === 'CANVAS'; }

  function sendMouseMove(target, x, y) {
    target.dispatchEvent(new MouseEvent('mousemove', { bubbles: true, cancelable: true, composed: true, clientX: x, clientY: y, view: window }));
  }
  function sendWheel(target, x, y, dx, dy) {
    target.dispatchEvent(new WheelEvent('wheel', {
      bubbles: true, cancelable: true, composed: true, clientX: x, clientY: y,
      deltaX: -dx, deltaY: -dy, deltaMode: 0, view: window
    }));
  }
  function stopMomentum() { if (momentum) { cancelAnimationFrame(momentum.raf); momentum = null; } }
  function startMomentum(target, x, y, vx, vy) {
    stopMomentum();
    if (Math.hypot(vx, vy) < 0.25) return;
    momentum = { vx: vx, vy: vy, last: performance.now(), raf: 0 };
    var m = momentum;
    (function frame(now) {
      if (momentum !== m) return;
      var dt = Math.min(48, now - m.last); m.last = now;
      var decay = Math.exp(-dt / 325);
      m.vx *= decay; m.vy *= decay;
      if (Math.hypot(m.vx, m.vy) < 0.03) { momentum = null; return; }
      sendWheel(target, x, y, m.vx * dt, m.vy * dt);
      m.raf = requestAnimationFrame(frame);
    })(m.last);
  }

  /* The press is held back until we know what the touch is, then replayed (tap / long-press) or dropped (scroll). */
  function replayPress(g) {
    if (!g || g.replayed) return;
    g.replayed = true;
    var c = g.target;
    try {
      c.dispatchEvent(new PointerEvent('pointerdown', {
        bubbles: true, cancelable: true, composed: true, pointerId: g.pid, pointerType: 'touch', isPrimary: true,
        clientX: g.x0, clientY: g.y0, button: 0, buttons: 1, pressure: 0.5, width: 1, height: 1, view: window
      }));
      var t = new Touch({ identifier: g.tid, target: c, clientX: g.x0, clientY: g.y0, pageX: g.x0, pageY: g.y0,
        screenX: g.x0, screenY: g.y0, radiusX: 1, radiusY: 1, force: 1 });
      c.dispatchEvent(new TouchEvent('touchstart', {
        bubbles: true, cancelable: true, composed: true, touches: [t], targetTouches: [t], changedTouches: [t], view: window
      }));
    } catch (err) { console.error('replayPress', err); }
  }
  function endGesture() { if (T) { clearTimeout(T.timer); } T = null; }

  function noteTap(g) {
    var now = Date.now();
    var dbl = lastTap && now - lastTap.t < 420 && Math.hypot(g.x0 - lastTap.x, g.y0 - lastTap.y) < 40;
    lastTap = dbl ? null : { t: now, x: g.x0, y: g.y0 };
    if (dbl) setTimeout(function () {
      // A double tap enters text editing in canvas editors (slides, cells). If the app did not focus its own text
      // input for it, do it for the app so the keyboard appears.
      if (Date.now() - lastAppFocus < 800) return;
      var a = findAgent();
      if (a) window.__acKeyboard(true);
    }, 300);
  }

  window.addEventListener('pointerdown', function (e) {
    if (!e.isTrusted || e.pointerType !== 'touch') return;
    if (!e.isPrimary) { if (T && T.mode === 'pending') { replayPress(T); T.mode = 'pass'; } if (T) T.mode = 'pass'; return; }
    if (!isCanvas(e.target) || !scrollEnabled()) { T = { mode: 'pass', replayed: true }; return; }
    stopMomentum();
    endGesture();
    T = { mode: 'pending', replayed: false, target: e.target, pid: e.pointerId, tid: 1,
      x0: e.clientX, y0: e.clientY, x: e.clientX, y: e.clientY, t0: performance.now(), samples: [], timer: 0 };
    T.timer = setTimeout(function () {
      if (T && T.mode === 'pending') { T.mode = 'pass'; replayPress(T); }
    }, HOLD_MS);
    e.stopImmediatePropagation();
  }, true);

  window.addEventListener('touchstart', function (e) {
    if (!e.isTrusted) return;
    if (e.touches.length > 1) { if (T) { if (T.mode === 'pending') replayPress(T); T.mode = 'pass'; clearTimeout(T.timer); } return; }
    if (T && T.mode === 'pending') { T.tid = e.touches[0].identifier; e.stopImmediatePropagation(); }
  }, true);

  window.addEventListener('touchmove', function (e) {
    if (!e.isTrusted || !T || T.mode === 'pass') return;
    var t = e.touches[0]; if (!t) return;
    var now = performance.now();
    if (T.mode === 'pending') {
      if (Math.hypot(t.clientX - T.x0, t.clientY - T.y0) > SLOP) {
        T.mode = 'scroll'; clearTimeout(T.timer);
        T.x = t.clientX; T.y = t.clientY; T.samples = [];
        sendMouseMove(T.target, t.clientX, t.clientY); // give egui a hover position so the right area scrolls
      } else { e.stopImmediatePropagation(); return; }
    }
    if (T.mode === 'scroll') {
      var dx = t.clientX - T.x, dy = t.clientY - T.y;
      T.x = t.clientX; T.y = t.clientY;
      T.samples.push({ t: now, dx: dx, dy: dy });
      while (T.samples.length && now - T.samples[0].t > 100) T.samples.shift();
      sendWheel(T.target, t.clientX, t.clientY, dx, dy);
      e.stopImmediatePropagation(); e.preventDefault();
    }
  }, { capture: true, passive: false });

  function finish(e) {
    if (!e.isTrusted || !T) return;
    if (T.mode === 'scroll') {
      e.stopImmediatePropagation();
      if (e.type === 'touchend' || e.type === 'pointercancel' || e.type === 'touchcancel') {
        var g = T, sum = { x: 0, y: 0 }, span = 0;
        if (g.samples.length) { span = Math.max(16, performance.now() - g.samples[0].t); g.samples.forEach(function (s) { sum.x += s.dx; sum.y += s.dy; }); }
        if (e.type === 'touchend' && span) startMomentum(g.target, g.x, g.y, sum.x / span, sum.y / span);
        endGesture();
      }
    } else if (T.mode === 'pending') {
      if (e.type === 'pointerup' || e.type === 'touchend') { noteTap(T); replayPress(T); T.mode = 'pass'; }
      else { endGesture(); } // cancelled before anything happened
    } else if (e.type === 'touchend' || e.type === 'pointercancel') {
      endGesture();
    }
  }
  ['pointerup', 'pointercancel', 'touchend', 'touchcancel'].forEach(function (n) { window.addEventListener(n, finish, true); });
})();
