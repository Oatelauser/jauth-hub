/*
 * /demo 教学区共享脚本（SPEC §7：原生 JS、零外链）。蓝本 D:\workspace\Java\test-oauth2 front/callback.html
 * 的 PKCE 客户端走法（code_verifier/state 存 sessionStorage、手工 code→token 交换），按 jauth-hub 教学区重写。
 */
(function (global) {
  'use strict';

  var VERIFIER_KEY = 'jauth_demo_verifier';
  var STATE_KEY = 'jauth_demo_state';
  var TOKEN_KEY = 'jauth_demo_tokens';

  function b64url(bytes) {
    var binary = '';
    for (var i = 0; i < bytes.length; i++) {
      binary += String.fromCharCode(bytes[i]);
    }
    return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  }

  function randomB64Url(byteLength) {
    var bytes = new Uint8Array(byteLength);
    global.crypto.getRandomValues(bytes);
    return b64url(bytes);
  }

  function sha256B64Url(input) {
    return global.crypto.subtle.digest('SHA-256', new TextEncoder().encode(input)).then(function (digest) {
      return b64url(new Uint8Array(digest));
    });
  }

  /* ---------------- HTTP 日志（B3 httplog fragment 的实时化：占位段落换终端式条目） ---------------- */

  var httplog = {
    container: null,

    init: function (title) {
      var section = document.querySelector('section.httplog');
      if (!section) {
        return false;
      }
      section.innerHTML = '';
      var heading = document.createElement('h2');
      heading.textContent = title;
      this.container = document.createElement('div');
      this.container.className = 'httplog-entries';
      this.container.setAttribute('aria-live', 'polite');
      section.appendChild(heading);
      section.appendChild(this.container);
      return true;
    },

    entry: function (text, level) {
      if (!this.container) {
        return;
      }
      var line = document.createElement('div');
      line.className = 'httplog-entry ' + (level || '');
      var time = document.createElement('span');
      time.className = 'httplog-time';
      time.textContent = new Date().toLocaleTimeString();
      line.appendChild(time);
      line.appendChild(document.createTextNode(' ' + text));
      this.container.appendChild(line);
      this.container.scrollTop = this.container.scrollHeight;
    },

    /* 记录并执行一次 fetch：method/URL/状态/耗时全程入日志，供教学区逐步观察 */
    fetch: function (method, url, options) {
      var self = this;
      var started = performance.now();
      this.entry(method + ' ' + url, 'req');
      return global.fetch(url, options).then(function (response) {
        return response.text().then(function (bodyText) {
          var elapsed = Math.round(performance.now() - started);
          self.entry(response.status + ' · ' + elapsed + 'ms · ' + summarize(bodyText), response.ok ? 'ok' : 'err');
          return { status: response.status, ok: response.ok, text: bodyText };
        });
      }, function (error) {
        self.entry('网络错误：' + error, 'err');
        throw error;
      });
    }
  };

  function summarize(bodyText) {
    var compact = String(bodyText).replace(/\s+/g, ' ').trim();
    return compact.length > 160 ? compact.slice(0, 160) + '…' : compact;
  }

  /* ---------------- 令牌与 PKCE 状态 ---------------- */

  function saveVerifierAndState(verifier, state) {
    global.sessionStorage.setItem(VERIFIER_KEY, verifier);
    global.sessionStorage.setItem(STATE_KEY, state);
  }

  function takeVerifierAndState() {
    var pair = {
      verifier: global.sessionStorage.getItem(VERIFIER_KEY),
      state: global.sessionStorage.getItem(STATE_KEY)
    };
    global.sessionStorage.removeItem(VERIFIER_KEY);
    global.sessionStorage.removeItem(STATE_KEY);
    return pair;
  }

  function saveTokens(tokens) {
    tokens.obtainedAt = Date.now();
    global.sessionStorage.setItem(TOKEN_KEY, JSON.stringify(tokens));
  }

  function readTokens() {
    var raw = global.sessionStorage.getItem(TOKEN_KEY);
    if (!raw) {
      return null;
    }
    try {
      return JSON.parse(raw);
    } catch (error) {
      return null;
    }
  }

  function clearTokens() {
    global.sessionStorage.removeItem(TOKEN_KEY);
  }

  function prettyJson(text) {
    try {
      return JSON.stringify(JSON.parse(text), null, 2);
    } catch (error) {
      return text;
    }
  }

  global.jauthDemo = {
    randomB64Url: randomB64Url,
    sha256B64Url: sha256B64Url,
    httplog: httplog,
    saveVerifierAndState: saveVerifierAndState,
    takeVerifierAndState: takeVerifierAndState,
    saveTokens: saveTokens,
    readTokens: readTokens,
    clearTokens: clearTokens,
    prettyJson: prettyJson
  };
})(window);
