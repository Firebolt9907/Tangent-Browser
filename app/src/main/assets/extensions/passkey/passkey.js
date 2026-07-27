/* Tangent passkey bridge — content script (GeckoView isolated world).
 *
 * Overrides navigator.credentials.create/get IN THE PAGE'S MAIN WORLD and routes the WebAuthn
 * ceremony to the native side (Bitwarden vault) over a connectNative Port. Doing the override from
 * the isolated content script — via Firefox's Xray `wrappedJSObject` + `exportFunction`/`cloneInto`
 * — means we never inject a page-world <script>, so the page's CSP `script-src` cannot block us
 * (the reason a naive shim fails on Google/GitHub). See AGENTS.md invariant #12 and
 * sync/webauthn/PasskeyBridge.kt for the native half.
 *
 * Trust model: the ceremony origin is ALWAYS this frame's real `location.origin` (read here in the
 * isolated world), never anything the page supplies. A hostile page can at most trigger a passkey
 * prompt for its own origin — exactly what a real navigator.credentials.get already allows — and the
 * native side re-validates rpId against this origin. Nothing cross-origin is reachable.
 *
 * Protocol (Port name "TangentPasskey"), all binary base64url (no padding):
 *   page → native : { id, op:"create"|"get", origin, rpId?, clientDataJSON,
 *                     // create: userId?, userName?, userDisplayName?, rpName?
 *                     // get:    allowCredentials:[credentialId...] }
 *   native → page : { id, ok:true,  ...outcome }  | { id, ok:false, fallback:true }
 *                   | { id, ok:false, error:"NotAllowedError"|..., message? }
 */
if (typeof browser !== 'undefined' && browser.runtime && browser.runtime.connectNative &&
    typeof exportFunction === 'function' && typeof cloneInto === 'function') {
  (function () {
    var win = window.wrappedJSObject;
    if (!win || !win.navigator || !win.navigator.credentials) return; // no WebAuthn surface here

    var port;
    try { port = browser.runtime.connectNative('TangentPasskey'); } catch (e) { return; }

    // Preserve the native implementations so we can fall back (native path / softtoken) when the
    // vault has no matching credential (native fallback was the chosen routing).
    var nativeCreate = win.navigator.credentials.create;
    var nativeGet = win.navigator.credentials.get;

    var seq = 0;
    var pending = Object.create(null); // id -> { resolve, reject, op, clientDataJSON }

    port.onMessage.addListener(function (msg) {
      if (!msg || msg.id == null) return;
      var p = pending[msg.id];
      if (!p) return;
      delete pending[msg.id];
      try { finish(p, msg); } catch (e) { try { p.reject(domException('UnknownError', String(e))); } catch (_) {} }
    });
    port.onDisconnect.addListener(function () {
      // Reject anything still in flight; the page can retry (falls through to native next time).
      for (var k in pending) {
        try { pending[k].reject(domException('UnknownError', 'passkey bridge disconnected')); } catch (e) {}
      }
      pending = Object.create(null);
    });

    // --- byte helpers -----------------------------------------------------

    function toBytes(x) {
      if (x == null) return null;
      var view;
      try {
        if (x.buffer && x.byteLength !== undefined) view = new Uint8Array(x.buffer, x.byteOffset || 0, x.byteLength);
        else view = new Uint8Array(x); // ArrayBuffer (possibly Xray-wrapped)
      } catch (e) { return null; }
      var out = new Uint8Array(view.length); // copy out of any Xray wrapper
      for (var i = 0; i < view.length; i++) out[i] = view[i];
      return out;
    }
    function b64url(bytes) {
      var s = '';
      for (var i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]);
      return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
    }
    function fromB64url(str) {
      var s = String(str).replace(/-/g, '+').replace(/_/g, '/');
      while (s.length % 4) s += '=';
      var bin = atob(s), out = new Uint8Array(bin.length);
      for (var i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
      return out;
    }
    function toArrayBuffer(bytes) {
      // A page-world ArrayBuffer (cloneInto below copies it across the world boundary).
      var buf = new ArrayBuffer(bytes.length);
      new Uint8Array(buf).set(bytes);
      return buf;
    }
    function strToArrayBuffer(str) {
      return toArrayBuffer(new TextEncoder().encode(str));
    }
    function domException(name, message) {
      // Build it in the page world so `instanceof DOMException` holds for page code.
      try { return new win.DOMException(message || '', name); } catch (e) { return new Error(name + ': ' + message); }
    }

    // --- request plumbing -------------------------------------------------

    function send(op, request, resolve, reject, clientDataJSON) {
      var id = 'pk' + (++seq);
      pending[id] = { resolve: resolve, reject: reject, op: op, clientDataJSON: clientDataJSON };
      request.id = id;
      request.op = op;
      request.origin = location.origin; // authoritative — never page-supplied
      request.clientDataJSON = clientDataJSON;
      try { port.postMessage(request); } catch (e) {
        delete pending[id];
        reject(domException('UnknownError', 'passkey bridge unavailable'));
      }
    }

    function finish(p, msg) {
      if (msg.ok) {
        p.resolve(cloneInto(
          p.op === 'create' ? buildCreateResult(msg, p.clientDataJSON) : buildGetResult(msg, p.clientDataJSON),
          window,
          { cloneFunctions: true },
        ));
      } else if (msg.fallback) {
        // Vault declined / had no matching credential → defer to the native authenticator.
        (p.op === 'create' ? p.nativeCreate : p.nativeGet)();
      } else {
        p.reject(domException(msg.error || 'NotAllowedError', msg.message));
      }
    }

    function buildGetResult(msg, clientDataJSON) {
      var rawId = fromB64url(msg.credentialId);
      var response = {
        clientDataJSON: strToArrayBuffer(clientDataJSON),
        authenticatorData: toArrayBuffer(fromB64url(msg.authenticatorData)),
        signature: toArrayBuffer(fromB64url(msg.signature)),
        userHandle: msg.userHandle ? toArrayBuffer(fromB64url(msg.userHandle)) : null,
      };
      return publicKeyCredential(msg.credentialId, rawId, response);
    }

    function buildCreateResult(msg, clientDataJSON) {
      var rawId = fromB64url(msg.credentialId);
      var attestationObject = fromB64url(msg.attestationObject);
      var authData = msg.authenticatorData ? fromB64url(msg.authenticatorData) : new Uint8Array(0);
      var response = {
        clientDataJSON: strToArrayBuffer(clientDataJSON),
        attestationObject: toArrayBuffer(attestationObject),
        getAuthenticatorData: function () { return toArrayBuffer(authData); },
        getPublicKey: function () { return null; }, // SPKI DER not surfaced; -7 alg is enough for RPs
        getPublicKeyAlgorithm: function () { return -7; },
        getTransports: function () { return ['internal', 'hybrid']; },
      };
      return publicKeyCredential(msg.credentialId, rawId, response);
    }

    function publicKeyCredential(idB64Url, rawIdBytes, response) {
      return {
        id: idB64Url,
        type: 'public-key',
        rawId: toArrayBuffer(rawIdBytes),
        authenticatorAttachment: 'platform',
        response: response,
        getClientExtensionResults: function () { return {}; },
      };
    }

    // --- overrides --------------------------------------------------------

    function isWebAuthn(options) {
      return options && options.publicKey && typeof options.publicKey === 'object';
    }

    function overrideCreate(options) {
      var opt = options && options.wrappedJSObject ? options.wrappedJSObject : options;
      if (!isWebAuthn(opt)) return nativeCreate.call(win.navigator.credentials, options);
      var pk = opt.publicKey;
      var challenge = toBytes(pk.challenge);
      if (!challenge) return nativeCreate.call(win.navigator.credentials, options);

      var clientDataJSON = JSON.stringify({
        type: 'webauthn.create', challenge: b64url(challenge), origin: location.origin, crossOrigin: false,
      });
      var user = pk.user || {};
      var request = {
        rpId: (pk.rp && pk.rp.id) || null,
        rpName: (pk.rp && pk.rp.name) || null,
        userId: user.id ? b64url(toBytes(user.id)) : null,
        userName: user.name || null,
        userDisplayName: user.displayName || null,
      };
      return new win.Promise(exportFunction(function (resolve, reject) {
        var p = null;
        send('create', request, resolve, reject, clientDataJSON);
        p = pending[request.id];
        if (p) { p.nativeCreate = function () { resolve(nativeCreate.call(win.navigator.credentials, options)); }; }
      }, window));
    }

    function overrideGet(options) {
      var opt = options && options.wrappedJSObject ? options.wrappedJSObject : options;
      if (!isWebAuthn(opt)) return nativeGet.call(win.navigator.credentials, options);
      var pk = opt.publicKey;
      var challenge = toBytes(pk.challenge);
      if (!challenge) return nativeGet.call(win.navigator.credentials, options);

      var clientDataJSON = JSON.stringify({
        type: 'webauthn.get', challenge: b64url(challenge), origin: location.origin, crossOrigin: false,
      });
      var allow = [];
      if (pk.allowCredentials && pk.allowCredentials.length) {
        for (var i = 0; i < pk.allowCredentials.length; i++) {
          var c = pk.allowCredentials[i];
          var idBytes = c && toBytes(c.id);
          if (idBytes) allow.push(b64url(idBytes));
        }
      }
      var request = { rpId: pk.rpId || null, allowCredentials: allow };
      return new win.Promise(exportFunction(function (resolve, reject) {
        send('get', request, resolve, reject, clientDataJSON);
        var p = pending[request.id];
        if (p) { p.nativeGet = function () { resolve(nativeGet.call(win.navigator.credentials, options)); }; }
      }, window));
    }

    try {
      win.navigator.credentials.create = exportFunction(overrideCreate, win.navigator.credentials);
      win.navigator.credentials.get = exportFunction(overrideGet, win.navigator.credentials);
    } catch (e) {
      // If the override can't be installed, leave the native surface untouched.
    }
  })();
}
