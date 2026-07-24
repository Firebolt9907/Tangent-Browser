# Self-Hosted FxA Push Bridge — Plan

Run a self-hosted Mozilla **autopush** server in **FCM-bridge mode** so Tangent receives Firefox
Sync "Send tab" pushes **instantly**, using your own Firebase project — with **no configuration in
Mozilla accounts, Zen, or any sending device.**

> Why this works with zero sender/Mozilla config: FxA stores each device's opaque WebPush endpoint
> and simply `POST`s to it. Swapping *only Tangent's* endpoint to your server is invisible to FxA and
> to every other device. See `AGENTS.md` → "Why FxA push doesn't work here" for the background.

---

## 1. Architecture

```
 Zen / Firefox ──sendCommandToDevice──► FxA (Mozilla, unchanged)
                                             │  WebPush POST to the endpoint Tangent registered
                                             ▼
                                   YOUR autopush  (autoendpoint, bridge mode)
                                             │  FCM HTTP v1 (your service account)
                                             ▼
                                        Google FCM ──► Tangent on the phone
```

- **FxA / Mozilla accounts:** untouched, still Mozilla-hosted. You keep using real accounts.
- **autopush (delivery only):** the single thing you run. Bridge mode → Google holds the device
  connections, your box only relays occasional sub-KB pushes. No persistent sockets to phones.
- **Tangent:** the only client that changes — its `AutoPushFeature` points at your endpoint, so its
  subscription endpoint lives on your host.

**Load reality:** hundreds of users = a few thousand tiny pushes/day, single-digit MB/day, sub-MB of
subscription data. Compute is a rounding error; the real cost is 24/7 uptime + a public HTTPS
endpoint + the storage-backend dependency below.

---

## 2. Components (docker-compose services)

| Service | Role | Notes |
|---|---|---|
| `autoendpoint` | autopush-rs HTTP node: registration + WebPush receipt + FCM relay | The only autopush piece we need. |
| **datastore** | autopush's persistence | autopush-rs targets **Bigtable**; see §4 for the homelab decision. |
| `cloudflared` | public HTTPS ingress | Recommended for homelab: no port-forward, no static IP, free TLS. (Alt: Caddy/Traefik + a port-forward.) |

> `autoconnect` (the WebSocket connection node, for desktop clients) is **not needed** — Tangent is an
> FCM client, so Google is the connection node.

---

## 3. Prerequisites

- [ ] A domain / subname, e.g. `push.yourdomain.com`.
- [ ] Firebase project (you already have one — sender `622681143514`) with a **service account JSON**
      granting Cloud Messaging (FCM HTTP v1). Download it; it's the credential autopush uses to send.
- [ ] Docker + Docker Compose on the homelab box.
- [ ] A Cloudflare account (if using the tunnel) with the domain on Cloudflare DNS.

---

## 4. The one real decision: the datastore

autopush-rs (current) is built around **Google Bigtable**. Pick one:

1. **Bigtable emulator** (`gcloud beta emulators bigtable`, shipped in the `cloud-sdk` image).
   Simplest to wire up, but **in-memory** — data is lost on restart. Consequence: on restart, stored
   subscriptions vanish and FxA's saved endpoints go stale until each client re-registers (Tangent
   re-registers/verifies on app launch, so it self-heals, but pushes in the gap fall back to
   polling). Acceptable for a hobby deployment if you rarely restart.
2. **Persistent Bigtable-compatible store** — run the emulator with a persisted volume, or a
   third-party Bigtable-compatible backend. More work; durable.
3. **Legacy Python `autopush` + DynamoDB Local** (`amazon/dynamodb-local`, persists to a volume).
   Durable and homelab-friendly, but you're on the older, less-maintained codebase.

**Recommendation:** start on the **emulator** to prove the pipeline end-to-end (§7), then decide
whether durability warrants moving to (2)/(3). The polling fallback means an emulator wipe never
loses a *tab* — only instant/background delivery, briefly.

---

## 5. docker-compose skeleton

> Config keys are **version-specific**. Pin a specific `autopush-rs` image tag and follow *that
> version's* settings docs — autopush uses `SECTION__KEY` double-underscore env overrides (e.g.
> `AUTOEND__FCM__...`). Treat the values below as the shape, not verbatim keys.

```yaml
services:
  bigtable-emulator:
    image: gcr.io/google.com/cloudsdktool/cloud-sdk:latest
    command: gcloud beta emulators bigtable start --host-port=0.0.0.0:8086
    # For durability, persist / swap per §4. Emulator is ephemeral by default.

  autoendpoint:
    image: mozilla/autopush-rs:<PINNED_TAG>      # pin a real tag
    depends_on: [bigtable-emulator]
    environment:
      # --- public identity: endpoints autopush hands out must use YOUR host ---
      AUTOEND__ENDPOINT_HOSTNAME: "push.yourdomain.com"
      AUTOEND__ENDPOINT_PORT: "443"
      AUTOEND__ENDPOINT_SCHEME: "https"
      # --- endpoint-URL signing key (generate one; see the version's docs / fernet) ---
      AUTOEND__CRYPTO_KEYS: "${AUTOPUSH_CRYPTO_KEY}"
      # --- FCM bridge: your Firebase service account ---
      AUTOEND__FCM__SERVER_CREDENTIALS: "/run/secrets/fcm-service-account.json"
      AUTOEND__FCM__PROJECT_ID: "tangent-browser-ede5c"
      # --- datastore: point at the emulator (or your persistent store) ---
      BIGTABLE_EMULATOR_HOST: "bigtable-emulator:8086"
      AUTOEND__DB_DSN: "grpc://bigtable-emulator:8086"   # verify exact form per version
    volumes:
      - ./secrets/fcm-service-account.json:/run/secrets/fcm-service-account.json:ro
    # no host ports published; cloudflared reaches it on the compose network
    expose: ["8000"]

  cloudflared:
    image: cloudflare/cloudflared:latest
    command: tunnel run
    environment:
      TUNNEL_TOKEN: "${CLOUDFLARE_TUNNEL_TOKEN}"
    # In the Cloudflare dashboard, route push.yourdomain.com -> http://autoendpoint:8000
```

`.env` (git-ignored): `AUTOPUSH_CRYPTO_KEY`, `CLOUDFLARE_TUNNEL_TOKEN`.

---

## 6. Client change in Tangent

One line in `FirefoxSyncController.initPush()` — point `AutoPushFeature` at your server instead of
Mozilla's default (`updates.push.services.mozilla.com`):

```kotlin
val push = AutoPushFeature(
    context = application,
    service = TangentFirebasePushService(),
    config = PushConfig(
        senderId = senderId,
        server = "push.yourdomain.com",   // your autopush host (default was Mozilla's)
        // protocol / serviceType default to https / FCM — confirm against the PushConfig you have
    ),
    crashReporter = crashReporter,
)
```

Everything else already in place stays: `FxaPushSupportFeature` registers the subscription with FxA,
`SendTabFeature` → notification + banner, and the **polling fallback remains the floor** if the
server is ever down (`AGENTS.md` → "Receiving Send tab").

---

## 7. Validation (do this in order — don't skip the endpoint-acceptance check)

1. **Bring up the stack**, confirm `autoendpoint` is healthy and reachable at
   `https://push.yourdomain.com` through the tunnel.
2. **Launch Tangent** → its registration `POST /v1/fcm/<app_id>/registration` should hit
   `autoendpoint` (watch its logs) and return a subscription whose endpoint is on **your** host.
3. **Confirm FxA accepted it** — the biggest unknown. Check that `currentDevice.subscription` is
   now non-null with an endpoint on your domain (reuse the temporary `TangentSendTab` diagnostic that
   logged `FxA currentDevice sub=…`, or re-add it briefly). *If FxA rejects a non-Mozilla endpoint
   domain, stop here — that kills the approach, and you fall back to polling.*
4. **Send a tab from Zen** → FxA should `POST` to your endpoint (autoendpoint logs show an incoming
   WebPush) → FCM relay → Tangent shows the banner/notification within a second or two.
5. **Kill the container**, send another tab → confirm the **polling fallback** still delivers it
   within ~25 s while foreground (proves graceful degradation).

---

## 8. Risks / unknowns

- **FxA endpoint allowlist** (step 7.3): WebPush says endpoints are opaque/arbitrary and Mozilla's
  own desktop/mobile split relies on that, so it *should* accept your domain — but verify before
  investing in durability/ops. This is the single go/no-go check.
- **autopush-rs config schema is version-specific** — pin a tag, follow its docs; the env keys above
  are illustrative.
- **VAPID pass-through:** FxA signs pushes with its VAPID key; ensure your autopush accepts them
  (default should, since you use the real FxA — just don't enable a restrictive allowlist).
- **Emulator durability** (§4): plan for re-registration churn or move to a persistent store.
- **Uptime is the real SLO,** not compute. Cloudflare Tunnel removes the static-IP/port-forward
  problem; power + ISP are your remaining weak links. A $5/mo VPS is the natural upgrade if users
  come to rely on instant delivery.

---

## 9. Rollback

Revert `PushConfig` to the default (drop the `server` arg) and redeploy. Push goes inert again, but
**Send Tab keeps working via polling** — no user-facing breakage at any point.
