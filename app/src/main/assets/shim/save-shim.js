/* Injected by the launcher at the top of every app's index.html.
 * Browser "downloads" (<a download href="blob:...">) don't do anything inside a WebView, so route them
 * to the native Save-as dialog through window.ArtcraftHost. */
(function () {
  if (window.__artcraftShim || !window.ArtcraftHost) return;
  window.__artcraftShim = true;
  var host = window.ArtcraftHost;
  var blobs = new Map();

  var origCreate = URL.createObjectURL.bind(URL);
  var origRevoke = URL.revokeObjectURL.bind(URL);
  URL.createObjectURL = function (obj) {
    var u = origCreate(obj);
    if (obj instanceof Blob) blobs.set(u, obj);
    return u;
  };
  URL.revokeObjectURL = function (u) {
    // apps often revoke right after click(); keep our reference a little longer
    setTimeout(function () { blobs.delete(u); }, 20000);
    return origRevoke(u);
  };

  function isDownload(a) {
    return !!a && a.tagName === 'A' && a.hasAttribute('download') && /^(blob:|data:)/.test(a.href);
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
    var token = host.beginSave(name, blob.type || 'application/octet-stream');
    try {
      var CH = 1 << 20;
      for (var off = 0; off < blob.size; off += CH) {
        var ok = host.writeChunk(token, await readB64(blob.slice(off, off + CH)));
        if (!ok) throw new Error('write failed');
      }
      host.finishSave(token);
    } catch (e) {
      host.abortSave(token);
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
})();
