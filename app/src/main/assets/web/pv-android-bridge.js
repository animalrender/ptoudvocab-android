/**
 * pv-android-bridge.js
 * ---------------------------------------------------------------------------
 * Implements `window.pvBridge` for the Android WebView host.
 *
 * The desktop build exposes the same object through an Electron preload script.
 * On Android the native side is the Kotlin `PvNative` object (added with
 * `WebView.addJavascriptInterface`). This file adapts it to the promise-based
 * API that shell.js and player.js already use, so those files stay unchanged.
 *
 *   invoke(channel, ...args) → Promise (resolved by native via __pvResolve)
 *   send(channel, ...args)   → fire-and-forget (pv:net-abort, pv:ready)
 *   on(channel, cb)          → subscribe to native events (__pvEmit)
 *
 * Paths: native never hands out real file-system paths on Android (files come
 * from the Storage Access Framework as content:// URIs). Every picked file gets
 * a *virtual path*  "/<base64url(contentUri)>/<display name>"  which behaves
 * like a normal path for the UI (the last segment is the file name) and can be
 * turned back into a content URI by the native side.
 * ---------------------------------------------------------------------------
 */
(function () {
  'use strict';
  if (window.pvBridge) return;
  var native = window.PvNative;
  if (!native) {
    console.warn('[pv-bridge] PvNative is missing – running outside the Android host?');
    native = {
      invoke: function (id) { window.__pvResolve(id, JSON.stringify({ __pvError: 'native bridge unavailable' })); },
      abort: function () {},
    };
  }

  var MEDIA_BASE = 'https://appassets.androidplatform.net/media/';
  var VIDEO_EXT = ['.mp4', '.m4v', '.mov', '.mkv', '.webm', '.ogv', '.avi', '.wmv', '.flv', '.mpg', '.mpeg', '.ts', '.m2ts', '.3gp', '.vob'];
  var SUBTITLE_EXT = ['.srt', '.vtt', '.ass', '.ssa', '.sub'];
  var AUDIO_EXT = ['.mp3', '.m4a', '.aac', '.ogg', '.opus', '.wav', '.flac'];

  var pending = new Map();
  var listeners = new Map();
  var seq = 0;

  function extOf(p) {
    var s = String(p || '');
    var dot = s.lastIndexOf('.');
    var sep = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
    return dot > sep ? s.slice(dot).toLowerCase() : '';
  }

  /** UTF-8 safe base64url (no padding) – same alphabet as Node's 'base64url'. */
  function b64url(str) {
    var bytes = new TextEncoder().encode(String(str));
    var bin = '';
    for (var i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
    return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  }

  /* Called by native code (MainActivity) when an invoke() finished. */
  window.__pvResolve = function (id, json) {
    var p = pending.get(Number(id));
    if (!p) return;
    pending.delete(Number(id));
    var data;
    try { data = json ? JSON.parse(json) : null; } catch (e) { p.reject(e); return; }
    if (data && typeof data === 'object' && data.__pvError) p.reject(new Error(data.__pvError));
    else p.resolve(data);
  };

  /* Called by native code for push events (menu, fullscreen, open-files …). */
  window.__pvEmit = function (channel, json) {
    var list = listeners.get(channel);
    if (!list || !list.length) return;
    var payload = null;
    try { payload = json ? JSON.parse(json) : null; } catch (e) { payload = null; }
    list.slice().forEach(function (cb) {
      try { cb(payload); } catch (err) { console.error('[pv-bridge] listener error', err); }
    });
  };

  var bridge = {
    invoke: function (channel) {
      var args = Array.prototype.slice.call(arguments, 1);
      return new Promise(function (resolve, reject) {
        var id = ++seq;
        pending.set(id, { resolve: resolve, reject: reject });
        try {
          native.invoke(id, String(channel), JSON.stringify(args));
        } catch (e) {
          pending.delete(id);
          reject(e);
        }
      });
    },
    send: function (channel) {
      var args = Array.prototype.slice.call(arguments, 1);
      if (channel === 'pv:net-abort') { try { native.abort(String(args[0])); } catch (e) { /* ignore */ } return true; }
      if (channel === 'pv:ready') { try { native.invoke(0, channel, '[]'); } catch (e) { /* ignore */ } return true; }
      return false;
    },
    on: function (channel, cb) {
      if (typeof cb !== 'function') return function () {};
      if (!listeners.has(channel)) listeners.set(channel, []);
      listeners.get(channel).push(cb);
      return function () {
        var arr = listeners.get(channel) || [];
        var i = arr.indexOf(cb);
        if (i >= 0) arr.splice(i, 1);
      };
    },
    /* Drag & drop of OS files does not exist on Android – files come from the picker. */
    getPathForFile: function () { return null; },
    mediaUrlFor: function (p) { return MEDIA_BASE + b64url(p); },
    localIdFor: function (p) { return 'pv-local://' + b64url(p); },
    isVideoFile: function (p) { return VIDEO_EXT.indexOf(extOf(p)) !== -1; },
    isSubtitleFile: function (p) { return SUBTITLE_EXT.indexOf(extOf(p)) !== -1; },
    isAudioFile: function (p) { return AUDIO_EXT.indexOf(extOf(p)) !== -1; },
    platform: 'android',
    versions: { platform: 'android' },
  };

  window.pvBridge = bridge;

  /* The shell tells native code that its listeners are attached; native then
     flushes files that arrived through ACTION_VIEW before the UI was ready. */
  document.addEventListener('pv-shell-ready', function () { bridge.send('pv:ready'); });
})();
