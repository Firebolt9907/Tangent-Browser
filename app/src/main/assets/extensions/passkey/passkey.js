/* Tangent passkey bridge — content script (GeckoView isolated world).
 *
 * Overrides navigator.credentials.create/get IN THE PAGE'S MAIN WORLD and routes the WebAuthn
 * ceremony to the selected native passkey provider over a connectNative Port. Doing the override from
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
      // Use the Xrayed page constructor: exported response methods must never return an
      // extension-owned ArrayBuffer to site code.
      var view = new window.Uint8Array(bytes.length);
      for (var i = 0; i < bytes.length; i++) view[i] = bytes[i];
      return view.buffer;
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
        if (p.op === 'uvpaa') {
          p.resolve(!!msg.available);
          return;
        }
        var result = msg.credentialManagerResponse
          ? buildCredentialManagerResult(p.op, msg.credentialManagerResponse, p.clientDataJSON)
          : (p.op === 'create'
              ? buildCreateResult(msg, p.clientDataJSON)
              : buildGetResult(msg, p.clientDataJSON));
        // `result` is deliberately constructed with the page window's constructors below. Passing
        // an extension-realm object to a page Promise leaves an Xray wrapper behind on Gecko; sites
        // that call PublicKeyCredential.toJSON() then fail with "Permission denied".
        p.resolve(result);
      } else if (msg.fallback) {
        // Vault declined / had no matching credential → defer to the native authenticator.
        (p.op === 'create' ? p.nativeCreate : p.nativeGet)();
      } else {
        p.reject(domException(msg.error || 'NotAllowedError', msg.message));
      }
    }

    function buildCredentialManagerResult(op, responseJson, clientDataJSON) {
      var value = JSON.parse(responseJson);
      var response = value.response || {};
      // With clientDataHash supplied, the browser—not the credential provider—owns these bytes.
      // Return exactly the JSON whose SHA-256 was sent to Credential Manager and signed by the
      // authenticator. Provider-side placeholder/copy bytes make strict RPs reject the ceremony.
      var browserClientDataBytes = new TextEncoder().encode(clientDataJSON);
      response.clientDataJSON = b64url(browserClientDataBytes);
      value.response = response;
      var id = value.id || value.rawId;
      var rawId = fromB64url(value.rawId || id);
      if (op === 'create') {
        var authData = response.authenticatorData ? fromB64url(response.authenticatorData) : new Uint8Array(0);
        var publicKey = response.publicKey ? fromB64url(response.publicKey) : null;
        var createResponse = pageObject({
          clientDataJSON: strToArrayBuffer(clientDataJSON),
          attestationObject: toArrayBuffer(fromB64url(response.attestationObject)),
        });
        pageMethod(createResponse, 'getAuthenticatorData', function () { return toArrayBuffer(authData); });
        pageMethod(createResponse, 'getPublicKey', function () { return publicKey ? toArrayBuffer(publicKey) : null; });
        pageMethod(createResponse, 'getPublicKeyAlgorithm', function () {
          return response.publicKeyAlgorithm == null ? -7 : response.publicKeyAlgorithm;
        });
        pageMethod(createResponse, 'getTransports', function () {
          return cloneInto(response.transports || ['internal', 'hybrid'], window);
        });
        return publicKeyCredential(
          id, rawId, createResponse, value.authenticatorAttachment,
          value.clientExtensionResults, value,
        );
      }
      var getResponse = pageObject({
        clientDataJSON: strToArrayBuffer(clientDataJSON),
        authenticatorData: toArrayBuffer(fromB64url(response.authenticatorData)),
        signature: toArrayBuffer(fromB64url(response.signature)),
        userHandle: response.userHandle ? toArrayBuffer(fromB64url(response.userHandle)) : null,
      });
      return publicKeyCredential(
        id, rawId, getResponse, value.authenticatorAttachment,
        value.clientExtensionResults, value,
      );
    }

    function buildGetResult(msg, clientDataJSON) {
      var rawId = fromB64url(msg.credentialId);
      var response = pageObject({
        clientDataJSON: strToArrayBuffer(clientDataJSON),
        authenticatorData: toArrayBuffer(fromB64url(msg.authenticatorData)),
        signature: toArrayBuffer(fromB64url(msg.signature)),
        userHandle: msg.userHandle ? toArrayBuffer(fromB64url(msg.userHandle)) : null,
      });
      return publicKeyCredential(msg.credentialId, rawId, response);
    }

    function buildCreateResult(msg, clientDataJSON) {
      var rawId = fromB64url(msg.credentialId);
      var attestationObject = fromB64url(msg.attestationObject);
      var authData = msg.authenticatorData ? fromB64url(msg.authenticatorData) : new Uint8Array(0);
      var response = pageObject({
        clientDataJSON: strToArrayBuffer(clientDataJSON),
        attestationObject: toArrayBuffer(attestationObject),
      });
      pageMethod(response, 'getAuthenticatorData', function () { return toArrayBuffer(authData); });
      pageMethod(response, 'getPublicKey', function () { return null; }); // SPKI DER not surfaced
      pageMethod(response, 'getPublicKeyAlgorithm', function () { return -7; });
      pageMethod(response, 'getTransports', function () {
        return cloneInto(['internal', 'hybrid'], window);
      });
      return publicKeyCredential(msg.credentialId, rawId, response);
    }

    function pageObject(properties) {
      // Constructors reached through the Xrayed window create objects owned by the page global.
      // They can cross the exported Promise boundary without exposing privileged wrappers.
      var out = new window.Object();
      if (properties) for (var key in properties) out[key] = properties[key];
      return out;
    }

    function pageMethod(target, name, fn) {
      exportFunction(fn, target, { defineAs: name });
    }

    function publicKeyCredential(idB64Url, rawIdBytes, response, attachment, extensionResults, jsonValue) {
      var credential = pageObject({
        id: idB64Url,
        type: 'public-key',
        rawId: toArrayBuffer(rawIdBytes),
        authenticatorAttachment: attachment || 'platform',
        response: response,
      });
      pageMethod(credential, 'getClientExtensionResults', function () {
        return cloneInto(extensionResults || {}, window);
      });
      pageMethod(credential, 'toJSON', function () {
        if (jsonValue) return cloneInto(jsonValue, window);
        // The custom-vault path predates Credential Manager's ready-made response JSON. Preserve
        // the WebAuthn Level 3 shape for callers that use PublicKeyCredential.toJSON().
        return cloneInto({
          id: idB64Url,
          rawId: idB64Url,
          type: 'public-key',
          authenticatorAttachment: attachment || 'platform',
          clientExtensionResults: extensionResults || {},
        }, window);
      });
      return credential;
    }

    function descriptorToJSON(value) {
      var bytes = value && toBytes(value.id);
      if (!bytes) return null;
      var out = { type: value.type || 'public-key', id: b64url(bytes) };
      if (value.transports && value.transports.length) out.transports = Array.from(value.transports);
      return out;
    }

    function creationOptionsToJSON(pk, challenge) {
      var user = pk.user || {};
      var value = {
        challenge: b64url(challenge),
        rp: {
          id: (pk.rp && pk.rp.id) || location.hostname,
          name: (pk.rp && pk.rp.name) || location.hostname,
        },
        user: {
          id: b64url(toBytes(user.id) || new Uint8Array(0)),
          name: user.name || '',
          displayName: user.displayName || user.name || '',
        },
        pubKeyCredParams: [],
      };
      if (pk.pubKeyCredParams) for (var i = 0; i < pk.pubKeyCredParams.length; i++) {
        value.pubKeyCredParams.push({ type: pk.pubKeyCredParams[i].type || 'public-key', alg: pk.pubKeyCredParams[i].alg });
      }
      if (pk.timeout != null) value.timeout = pk.timeout;
      if (pk.attestation) value.attestation = pk.attestation;
      copyJsonOption(pk, value, 'extensions');
      copyJsonOption(pk, value, 'hints');
      copyJsonOption(pk, value, 'attestationFormats');
      if (pk.authenticatorSelection) {
        var selection = pk.authenticatorSelection;
        value.authenticatorSelection = {};
        if (selection.authenticatorAttachment) value.authenticatorSelection.authenticatorAttachment = selection.authenticatorAttachment;
        if (selection.residentKey) value.authenticatorSelection.residentKey = selection.residentKey;
        if (selection.requireResidentKey != null) value.authenticatorSelection.requireResidentKey = selection.requireResidentKey;
        if (selection.userVerification) value.authenticatorSelection.userVerification = selection.userVerification;
      }
      if (pk.excludeCredentials) {
        value.excludeCredentials = [];
        for (var j = 0; j < pk.excludeCredentials.length; j++) {
          var descriptor = descriptorToJSON(pk.excludeCredentials[j]);
          if (descriptor) value.excludeCredentials.push(descriptor);
        }
      }
      return JSON.stringify(value);
    }

    function requestOptionsToJSON(pk, challenge) {
      var value = {
        challenge: b64url(challenge),
        rpId: pk.rpId || location.hostname,
      };
      if (pk.timeout != null) value.timeout = pk.timeout;
      if (pk.userVerification) value.userVerification = pk.userVerification;
      copyJsonOption(pk, value, 'extensions');
      copyJsonOption(pk, value, 'hints');
      if (pk.allowCredentials) {
        value.allowCredentials = [];
        for (var i = 0; i < pk.allowCredentials.length; i++) {
          var descriptor = descriptorToJSON(pk.allowCredentials[i]);
          if (descriptor) value.allowCredentials.push(descriptor);
        }
      }
      return JSON.stringify(value);
    }

    function copyJsonOption(source, destination, name) {
      if (source[name] == null) return;
      // WebAuthn extension inputs used by Google (for example `appid`) are JSON-compatible. Ignore
      // exotic binary extension inputs here rather than corrupting the whole Credential Manager
      // request; the core ceremony fields remain complete.
      try { destination[name] = JSON.parse(JSON.stringify(source[name])); } catch (_) {}
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
        requestJson: creationOptionsToJSON(pk, challenge),
      };
      return new window.Promise(exportFunction(function (resolve, reject) {
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
      var request = {
        rpId: pk.rpId || null,
        allowCredentials: allow,
        requestJson: requestOptionsToJSON(pk, challenge),
      };
      return new window.Promise(exportFunction(function (resolve, reject) {
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

    // Gecko's legacy Android FIDO2 backend reports no platform authenticator when this browser is
    // absent from Google's global allowlist. In modes that install this bridge, Credential Manager
    // (or the vault provider) is the platform authenticator, so capability-gated sites such as
    // Google must see the bridge's real availability before they will call create().
    try {
      if (win.PublicKeyCredential &&
          typeof win.PublicKeyCredential.isUserVerifyingPlatformAuthenticatorAvailable === 'function') {
        exportFunction(
          function () {
            return new window.Promise(exportFunction(function (resolve, reject) {
              send('uvpaa', { rpId: null }, resolve, reject, '');
            }, window));
          },
          win.PublicKeyCredential,
          { defineAs: 'isUserVerifyingPlatformAuthenticatorAvailable' },
        );
      }
    } catch (_) {
      // A read-only implementation is safe to leave untouched; create/get still work on sites that
      // don't gate their ceremony on this advisory capability probe.
    }
  })();
}
