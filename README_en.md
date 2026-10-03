# 🛡️ jauth-hub

**A GitHub-style OAuth 2.1 + OIDC authorization server** — one codebase that deploys standalone as your central auth service, or embeds into your own Spring Boot service as its auth module.

English | [中文](README.md)

![Version](https://img.shields.io/badge/version-1.5.0-blue) ![Java](https://img.shields.io/badge/Java-21-orange) ![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.x-brightgreen) ![License](https://img.shields.io/badge/License-MIT-yellow) ![CI](https://github.com/Oatelauser/jauth-hub/actions/workflows/ci.yml/badge.svg)

---

## ✨ Why jauth-hub

| 💡 Highlight | What you get |
|---|---|
| 🐙 **GitHub-grade UX** | Scope checkboxes on the consent page, an authorized-apps dashboard with one-click revoke, personal access tokens, a `/me` platform endpoint — familiar to anyone who has wired up a GitHub App |
| 🏢 **v1.1 platform layer** | Self-service organizations, two-step app-installation approval with OWNER-set scope ceilings, issued scopes = requested ∩ consented ∩ ceiling (intersected at runtime), an enriched `orgs` claim (id/name/role), app registration for personal and org apps, user management and self-service password change |
| 🔑 **v1.2 Passkey** | WebAuthn passkeys: a passwordless button on the login page plus a self-service management page (register/delete); the private key never leaves the device — off by default, one switch to enable |
| 🧹 **v1.3 hardening sweep** | Account-security closed loop: password change / disable revokes every token and session (fail-secure); full app lifecycle (secret rotation / edit / delete-with-cascade); org member management (OWNER surface); sensitive scopes auto-imply sudo; creation throttling; audit vocabulary completion |
| 🧑‍💻 **v1.5 frontend unification** | `jauth-hub-front` (Vue 3 + Vite) is the **one and only official frontend**: the SSR skin is retired, every page route 302s to `/front/<route>` (query string forwarded verbatim); embedding hosts get the **whole UI for free** by depending on `jauth-hub-front-dist` (same-domain `/front/**` static assets + history deep-link fallback); all page data flows through the JSON state/action endpoints |
| 🔀 **Dual mode, one codebase** | **Standalone**: run one service and point every project at it. **Embedded**: drop in a starter and auth grows inside your own service (the host only supplies a `UserDetailsService`) |
| 🔐 **Security-first core** | Only SHA-256 hashes of tokens ever hit the database (a DB leak ≠ a token leak), refresh token rotation with **whole-family revocation on replay**, mandatory PKCE, signing keys auto-rotated every 90 days |
| 🎓 **Built to teach** | The bundled `/demo` walkthrough drives a real authorization-code + PKCE flow against real endpoints, showing every HTTP request/response live — frontend engineers get OAuth in one sitting |
| 🧩 **Family ecosystem** | Plays out of the box with the [spring-plus](https://central.sonatype.com/search?q=io.github.oatelauser) family (unified responses / declarative authorization / config encryption), yet works fully standalone |
| 🗄️ **Zero-ceremony start** | Defaults to an H2 file database (clone and run), switches to PostgreSQL with one line of config; SQL is written for both dialects |
| 🧪 **Quality gates** | 496 tests + 80 front-end vitest + Alibaba p3c rules + Spotless + SpotBugs/FindSecBugs + full end-to-end flow tests, all enforced green in CI |

## 📦 Modules

```
jauth-hub-core                    Protocol + domain implementation (storage/tokens/keys/pages/SPI, zero family deps)
jauth-hub-starter                Takeover-style auto-configuration (memory|jdbc conditional wiring; add the dep and it works)
jauth-hub-selfservice            User self-service pages (authorized apps, PAT, passkeys, my orgs, my apps, installation approval)
jauth-hub-resource-server-starter Resource-server integration (introspection + 30s cache + scope→authority mapping)
jauth-hub-app                    Standalone deployment shell (own user store + /demo teaching zone)
jauth-hub-front                  Separated frontend project (Vue 3 + Vite, outside the Maven reactor; optional skin for the trust-surface pages)
jauth-hub-front-dist             Jar artifact packaging the front dist (in the reactor; add the dep and the /front/** static skin is yours)
examples/embedded-demo           Embedded-integration example project
```

## 🛠️ Prerequisites

| Requirement | Version | Notes |
|---|---|---|
| ☕ JDK | 21+ | Check with `java -version` |
| 📦 Maven | 3.9+ | Or use your IDE's bundled one |
| 🐘 PostgreSQL | 16+ (optional) | Production store; development defaults to the H2 file database, no install needed |
| 🐳 Docker (optional) | any | Only for PG integration tests (CI runs them automatically) |

```bash
git clone https://github.com/Oatelauser/jauth-hub.git
cd jauth-hub
mvn verify   # build + full test suite + quality gates
```

## 🚀 Quick start

### ① An authorization server in three minutes

```bash
mvn -pl jauth-hub-app -am package -DskipTests
java -jar jauth-hub-app/target/jauth-hub-app-1.5.0.jar
```

Open <http://localhost:8080/demo> — the teaching zone walks you through the full **login → consent → token exchange → introspection → API call** loop, with live HTTP details at every step.

The first boot creates a superadmin (defaults for the local profile live in `application-local.yml`; see `application-dev.yml` / `application-prod.yml` for the others):

```yaml
jauth-hub:
  bootstrap:
    superadmin:
      username: admin
      password: admin-dev-only-placeholder   # ⚠️ change for production; supports ENC() ciphertext
```

### ② Integrating a project (pick one)

**Embedded mode** — auth lives inside your own service:

```xml
<dependency>
    <groupId>io.github.oatelauser</groupId>
    <artifactId>jauth-hub-starter</artifactId>
    <version>1.5.0</version>
</dependency>
```

```java
@Bean
UserDetailsService userDetailsService() {
    return username -> myUserService.load(username);  // this one Bean is all you owe
}
```

**Resource-server mode** — your API validates tokens issued by jauth-hub:

```xml
<dependency>
    <groupId>io.github.oatelauser</groupId>
    <artifactId>jauth-hub-resource-server-starter</artifactId>
    <version>1.5.0</version>
</dependency>
```

```yaml
jauth-hub.rs:
  introspection-uri: http://auth-host:8080/introspect
  client-id: my-resource-server      # a confidential client registered at the auth server
  client-secret: ${RS_SECRET}
```

A complete runnable example lives in `examples/embedded-demo`.

### ③ Separated frontend skin (jauth-hub-front, v1.4 headless)

The four trust-surface pages (login / consent / device verification / sudo) expose JSON APIs any frontend can consume; the root-level `jauth-hub-front/` (Vue 3 + Vite, outside the Maven reactor) is the official separated-frontend reference:

| JSON API | Method | Page |
|---|---|---|
| `/api/login` | GET state + POST auth bridge (returns redirectUrl) | Login |
| `/api/consent` | GET assembled state (scope items / org tri-state / granted badges) | Consent |
| `/api/device/verify` | GET state | Device verification |
| `/api/sudo` | GET state | sudo re-verification |

Deployment (same domain; as of v1.5 front is the one and only skin and the UI is **bundled via the `jauth-hub-front-dist` module**):

```bash
cd jauth-hub-front && npm ci && npm run build     # produces dist/
cd .. && mvn -Pdist -pl jauth-hub-app -am package # dist enters the jar via the jauth-hub-front-dist dependency
java -jar jauth-hub-app/target/jauth-hub-app-1.5.0.jar --jauth-hub.trust-skin=front
```

The app takes the dist jar artifact as a compile dependency: a `-Pdist` full-chain build embeds `classpath:/static/front` in the executable jar; a plain build without the profile resolves an empty jar (no UI embedded — the reactor is never hijacked by npm).

Every page GET (login/consent/device-verify/sudo plus all selfservice/admin/demo pages) 302s to `/front/<route>` (query string forwarded verbatim); static assets are served under `/front/**` with history deep-link fallback. Local no-repackage iteration (start from `jauth-hub-app/`): `--spring.web.resources.static-locations=file:../jauth-hub-front/dist` (note the property replaces the default static locations entirely).

## 📖 User guide

### 👤 End users

| Action | Where | Notes |
|---|---|---|
| 🔑 Sign in | `/login` | Username/password; with passkeys enabled there is also a "Sign in with a passkey" button (device biometrics, private key never leaves the device) |
| 👤 Profile & password | `/profile` | Change display name; self-service password change (old-password check is brute-force aware) |
| ✅ Consent | The consent page after the auth redirect | Check scopes individually — issued tokens carry only what you allowed; org apps show their organization |
| 📋 Authorized apps | `/selfservice/apps` | See who holds your authorizations; **revoke** in one click |
| 🏢 My organizations | `/selfservice/my-orgs` | Create orgs yourself, view role badges; OWNERs enter installation approval from here |
| ✉️ Installation approval | `/selfservice/orgs/{orgId}/installations` | OWNERs rule on members' app-installation requests: approve with a ceiling ⊆ requested scopes, reject, or revoke |
| 🧩 My apps | `/selfservice/my-apps` | Register personal OAuth apps (client_id/secret, redirect allowlist); org apps live at `/selfservice/orgs/{orgId}/apps` |
| 🔖 Personal access tokens | `/selfservice/pat` | Pick scopes + lifetime (30/90/365 days); the plaintext is **shown exactly once** |
| 🔐 Passkeys | `/selfservice/passkey` | Register and manage passkeys (name them, delete to revoke); off by default, see the configuration table |
| 🔐 Sudo verification | `/selfservice/sudo` | In-session passkey step-up page shown when a sensitive operation is blocked (A0515); returns to the original form afterwards; off by default |

> **Issued scopes for org apps** = requested ∩ user consent ∩ org ceiling, intersected at runtime — narrow any of the three and the token narrows with it.

### 🛠️ Administrators

```bash
# Create a regular user (since v1.1 the /admin/users page is the recommended path:
# create users, flip role/status, reset passwords; raw SQL remains equivalent — password is a bcrypt hash)
INSERT INTO jauth_user (id, username, password_hash, display_name, role, status, created_at)
VALUES ('0192...', 'zhangsan', '{bcrypt}$2a$10$...', 'Zhang San', 'USER', 'ACTIVE', NOW());
```

```yaml
# Register a client (properties seeding; upserted at startup, idempotent)
jauth-hub:
  clients:
    - client-id: my-webapp
      client-secret: "{noop}dev-secret"     # switch to bcrypt/ENC for production
      grant-types: [authorization_code, refresh_token]
      redirect-uris: [https://my-webapp.example.com/login/oauth2/code]
      scopes: [openid, profile]
```

### 💻 Developers (calling the API)

```bash
# Authorization code for a token (public client + PKCE)
curl -X POST http://localhost:8080/oauth2/token \
  -d grant_type=authorization_code -d code=... \
  -d redirect_uri=... -d client_id=my-webapp \
  -d code_verifier=...

# Introspection (resource-server side)
curl -u my-resource-server:$RS_SECRET \
  -d token=... http://localhost:8080/introspect

# Revocation
curl -u my-webapp:secret -X POST http://localhost:8080/oauth2/revoke -d token=...
```

OIDC discovery endpoint: <http://localhost:8080/.well-known/openid-configuration> (Spring clients plug in with zero config)

## ⚙️ Key configuration

| Property | Default | Notes |
|---|---|---|
| `jauth-hub.storage` | `memory` (app pins `jdbc`) | In-memory = light demo; jdbc = production |
| `jauth-hub.issuer` | `http://localhost:8080` | Issuer address |
| `jauth-hub.educational` | `true` | Teaching blocks toggle (turn off in production) |
| `jauth-hub.rate-limit.limit-per-hour` | `5000` | Per-user merged rate limiting |
| `jauth-hub.rate-limit.login-max-failures` | `5` | 5 misses lock the account for 15 minutes |
| `jauth-hub.cors.allowed-origins` | empty | Set when an SPA hits the token endpoint directly from the browser |
| `jauth-hub.passkey.enabled` | `false` | Passkey sign-in and management toggle; `rp-id`/`allowed-origins` default to values derived from `issuer` (host as rp-id, full URL as origin); startup fails fast when not derivable |
| `jauth-hub.sudo.enabled` | `false` | sudo re-verification toggle (depends on passkey; enabling it alone fails startup); sensitive operations (password change / admin password reset / role change) outside the strong-auth TTL get A0515 and the page redirects to the verification page |
| `jauth-hub.sudo.ttl-minutes` | `15` | sudo strong-auth validity window in minutes; a successful passkey verification stamps `strong_auth_at`, no re-verification inside the window |

**Default policies** (overridable per client): access 2h · refresh 30d, rotate on use (replay → whole family revoked) · PAT 90d · authorization code 5min + mandatory PKCE · keys rotate every 90d with a 14d overlap. ⚠️ Framework guardrail: public clients never receive refresh tokens.

## 🗺️ Roadmap

- **v1.1 (platform layer)**: organizations, app-installation approval with scope ceilings, app/user management pages ✅
- **v1.2 (hardening layer)**: Passkey passwordless sign-in, sudo mode for sensitive operations, @RequiresScope declarative scope checks ✅
- **v2+**: webhook events, secret scanning, email flows, ...
- **v1.3 (hardening sweep)**: credential-state mass revocation, app rotation/edit/delete-with-cascade, org member management, consent already-granted badge, creation throttling, user pagination, audit vocabulary completion ✅
- **v1.4 (headless skin)**: trust-surface JSON APIs + `jauth-hub-front` (Vue 3 separated-frontend reference) ✅
- **v1.5 (frontend unification, this release)**: SSR skin retired, `jauth-hub-front` the one official frontend (enterprise design system + nav shell + demo teaching zone), `jauth-hub-front-dist` jar dual-form distribution (embedders get the UI for free) ✅

## 📚 More docs

- 📐 [Architecture SPEC](docs/SPEC.md) — the implementation constitution (full decisions on modules/tables/endpoints/policies; in Chinese)
- 🗺️ [Decision map](.scratch/jauth-hub/map.md) — the story behind all 10 decision tickets
- 🔧 [Contributing & quality gates](AGENTS.md) · [Release process](docs/RELEASE_PROCESS.md)

## 📄 License

MIT © 2026
