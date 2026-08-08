# tinox-central — Plan v1

Central package registry for the tinox ecosystem: a REST API written in
tinox itself (annotation-driven, per project convention) plus a
Quarkus + Vaadin frontend for browsing/searching published packages.

This supersedes the "static git-index" idea discussed earlier
(subnix-work/tinox#172) — that issue's `[registry] url = "..."` client-side
config still applies (this service just becomes the default value), but
publishing now goes through a real HTTP API with admin auth instead of
hand-editing index files via PRs.

Status: **planning only, nothing implemented yet.** This file intentionally
lives uncommitted in the repo (working notes), not a design doc for review.

---

## 1. Scope (v1)

- `GET /api/v1/<group>/<artifactId>/<version>` — download one exact
  version's artifact (binary, e.g. `.tar.gz`/`.zip`/`.tnx`).
- `POST /api/v1/<group>/<artifactId>/<version>` — publish that version.
  Requires `Authorization: Bearer <admin-api-key>`; every other caller
  gets 401/403. Single admin (you) for v1 — no multi-user accounts,
  no per-package ownership.
- `GET /api/v1/<group>/<artifactId>` — list all published versions of one
  package (needed by both the CLI and the frontend's package detail page).
- `GET /api/v1/packages` (or `/api/v1/search?q=`) — flat catalog for the
  frontend's browse/search view.
- Quarkus + Vaadin frontend: package list view (Grid) with a search box,
  package detail view (versions, download links, publish date, size,
  checksum).

Explicitly **out of scope for v1** (matches CLAUDE.md's "gezielt statt
pauschal" — narrow, well-bounded v1 over a speculative big-bang):

- Multi-user accounts / per-package ownership / package transfer.
- Semver range resolution (tinox's `pm.rs` today always pins an *exact*
  version — see `Dependency.version` in `crates/tinox/src/pm.rs` — so the
  registry only ever needs exact-match lookup, not "give me `^1.2`").
  Deferred to whenever pm.rs itself grows range support.
- Yanking/deleting a published version.
- Wiring `tinox add`/`tinox install` (pm.rs) to actually *call* this
  registry — that's the tracked-separately follow-up to #172. This plan
  only covers building the registry service + UI. Once it's live, a
  small pm.rs change resolves `group:artifactId:version` against
  `[registry].url` before falling back to an explicit `url` field.
- Rate limiting / abuse protection beyond what a reverse proxy gives for
  free.

---

## 2. Architecture

```
                    ┌───────────────────────────┐
tinox CLI (pm.rs) ─▶│                           │
curl / browser    ─▶│  reverse proxy (TLS term) │
Quarkus backend   ─▶│  nginx or Caddy           │
                    └─────────────┬─────────────┘
                                  │ plain HTTP, loopback/internal
                                  ▼
                    ┌───────────────────────────┐
                    │ registry-backend (tinox)  │
                    │ RestApi + RestController  │
                    │ annotations, single proc  │
                    └─────────────┬─────────────┘
                                  │ Fs::* (filesystem)
                                  ▼
                    ┌───────────────────────────┐
                    │ data/ (index + artifacts) │
                    └───────────────────────────┘

                    ┌───────────────────────────┐
                    │ registry-frontend         │
                    │ Quarkus + Vaadin (Java)   │
                    │ calls the same REST API   │
                    │ over HTTP (its own proc)  │
                    └───────────────────────────┘
```

Two independent processes/deployables (tinox backend, Quarkus/Vaadin
frontend) talking only over the public REST API — the frontend has no
special access, it's just the first "real" consumer besides the CLI,
which keeps the API honest.

### Why a reverse proxy in front of the tinox backend

`RestApi` (the annotation-driven controller wiring, `tinox.core.rest.server`)
only exposes `RestApi::start()` — no `startTls`/TLS variant. TLS is only
available on the lower-level `HttpServer::listenTls()`, which would mean
giving up `@GET`/`@Path`/`@Auth`/`RestController` entirely and hand-wiring
routes. Terminating TLS in front (nginx/Caddy, both trivial with Let's
Encrypt) is the standard shape anyway and lets the backend stay 100%
annotation-driven. The frontend needs a public HTTPS endpoint too, so one
reverse proxy in front of both is the natural setup.

---

## 3. Repo layout

```
tinox-central/
  LICENSE
  PLAN.md                        (this file — not committed)
  registry-backend/              (tinox)
    tinox.toml
    src/
      Main.tnx                   (RestApi wiring: routes, setAuthValidator,
                                   app.start() — see §7.2/§7.2b, no
                                   RestController subclassing, no
                                   auto-generated main)
      PackageController.tnx      (plain class, NOT extending RestController
                                   — static fnc handlers called from the
                                   closures registered in Main.tnx)
      PackageStore.tnx           (fs-backed read/write, one class = one job)
      PackageMeta.tnx            (@JsonSerializable — one version's record)
      PackageSummary.tnx         (@JsonSerializable — catalog list entry)
    tests/
      e2e/
        publish_and_download.tnx (real HTTP client hitting a live instance)
  registry-frontend/              (Quarkus + Vaadin, Java/Maven)
    pom.xml
    src/main/java/io/tinox/central/frontend/
      RegistryClient.java         (REST client against registry-backend)
      PackageListView.java        (Vaadin @Route "/", Grid + search box)
      PackageDetailView.java      (Vaadin @Route "/packages/:group/:artifact")
    src/main/resources/
      application.properties
```

One class/interface/enum per `.tnx` file, matching the hard compiler rule
in `tinox`'s own CLAUDE.md (this repo isn't `tinox` itself, so the rule
isn't compiler-enforced here, but there's no reason to diverge from a
convention you already rely on elsewhere).

---

## 4. Data model & storage

No SQL/DB module exists in tinox's stdlib today (checked `docs_en.html`'s
full module list — no `mod-sql`/`mod-postgres`/etc.), so v1 storage is
filesystem-based via `tinox.core.fs`, using the exact same layout shape
already floated for the static-index idea in #172 — just written by the
API instead of by hand/PR:

```
data/
  index/
    <group>/
      <artifactId>/
        <version>.json     # PackageMeta: group, artifactId, version,
                            # sha256, sizeBytes, publishedAt, filename
  artifacts/
    <group>/
      <artifactId>/
        <version>/
          <filename>        # the actual artifact bytes
```

**Single source of truth = the per-version JSON files on disk.** The
in-memory catalog (`List<PackageSummary>` inside `PackageStore`, an
`@ApplicationComponent` singleton) is rebuilt by walking `index/` at
startup and updated in place on each publish — deliberately *not* a
separately-maintained `catalog.json` that could drift from the per-version
files. This project has been bitten before by exactly this class of bug
(two representations of the same fact silently diverging — see
subnix-work/tinox#154, `tinox.yaml` vs `tinox.toml`), so it's worth being
explicit that this design avoids introducing a second one on purpose.

---

## 5. API design (concrete)

### `GET /api/v1/{group}/{artifactId}/{version}`

- 200, JSON body `{"filename": "...", "sha256": "...", "sizeBytes": N,
  "contentBase64": "..."}` (see §7.1 — base64 is a confirmed-necessary
  decision, not a stylistic choice). `Content-Type: application/json`.
- 404 if group/artifactId/version combination doesn't exist.

### `POST /api/v1/{group}/{artifactId}/{version}`

- Requires `Authorization: Bearer <key>` — `@Auth("bearer")` on the method.
- Body: `{"filename": "...", "contentBase64": "..."}`. `filename` is used
  only for `Content-Disposition`-equivalent metadata on download, never
  as a filesystem path component directly (still runs through the same
  path-safety handling as group/artifactId/version — see §7.4).
- 409 if that exact version already exists (**no overwrite in v1** —
  immutable versions, standard registry practice; re-publish under a new
  version instead).
- 400 on invalid group/artifactId/version (see validation below), invalid
  base64, or empty content.
- 201 on success, body = the stored `PackageMeta` (including the
  **server-computed** sha256 — see below).
- Server decodes `contentBase64` via `Base64::decodeBytes` into a
  `List<Int64>` and hashes it via the **`sha256Bytes(data: List<Int64>) ->
  List<Int64>`** global builtin (`crates/tinox-typecheck/src/lib.rs:1003`,
  backed by `sha256_raw` in `runtime.c`) — **not** `Crypto::sha256`, which
  takes a `String` and is exactly as `strlen`-based/broken as everything
  else in §7.1 (`sha256Hash` in `runtime.c:1869` calls `strlen(data)`
  directly). `sha256Bytes` is already used internally by
  `Amqp10.tnx`'s SCRAM auth but isn't wrapped as a public `Crypto::`
  method yet — callable directly as a bare builtin from our project code
  without needing an `extern fn` declaration (same as `fileReadAllText`
  in `Fs.tnx`), no stdlib change needed. The resulting 32-byte digest
  also needs hex-encoding without going through `Hex::encode` (same
  `String`/`strlen` problem, and no `Hex::encodeBytes` exists) — a small
  local `bytesToHex(data: List<Int64>) -> String` helper (nibble lookup,
  ~10 lines, mirrors `Hex::nibbleToChar`) in `PackageStore.tnx` covers
  this; stores that as the canonical hash. It does not trust a
  client-supplied hash for the artifact it's about to serve to everyone
  else. (A client-supplied hash, if sent, is only useful as a
  client-side upload-integrity check — compare it to the server's
  response and fail loudly on mismatch, don't silently accept whatever
  the server computed.)

### `GET /api/v1/{group}/{artifactId}`

- 200, JSON array of `PackageMeta` (all versions), sorted by
  `publishedAt` descending. 404 if the package doesn't exist at all.

### `GET /api/v1/packages`

- 200, JSON array of `PackageSummary` (group, artifactId, latest version,
  version count, latest publishedAt). Powers the frontend's list/search
  view; search itself (`?q=`) is a client-side/in-memory substring
  filter over this same list for v1 — no need for anything fancier at
  expected scale (see #172's original registry-API sketch, same
  reasoning applied there for the static-index variant).

### Request/response body format — binary safety

**Open question to spike first, see §7.** If it turns out `HttpRequest`/
`HttpResponse` bodies (`String`-typed) don't round-trip arbitrary bytes
including embedded `0x00` safely, the fallback is: artifact bytes travel
as base64 inside a small JSON envelope (`{"filename": "...",
"contentBase64": "..."}`) for both POST and GET, and are stored base64-
encoded on disk too (sidesteps the question entirely at the cost of ~33%
size overhead, and matches what the `hex`/`base64` module docs already
hint at — see §7.1).

---

## 6. Auth design

**Updated per §7.2b's spike finding (bug #173) — no `@Auth`
annotation/`AuthValidator`-class-discovery, no `RestController`
subclassing.** Both of those belong to the auto-generated-`main`
compilation mode we're deliberately not using (§7.2). Auth is wired
explicitly instead, using stock `RestApi`/`RestController` methods —
validated end-to-end in the spike (§7.2b):

```tinox
// Main.tnx
import tinox.core.rest.server;
import tinox.core.env;
import PackageController;

class Main
{
    fnc main() -> Int32
    {
        let app: RestApi = RestApi::new(8080);
        app.setAuthValidator(fnc(authType: String, credential: String) -> Bool {
            if authType != "bearer" { return false; }
            let expected: String = Env::getVar("TINOX_CENTRAL_ADMIN_KEY");
            if expected == "" { return false; } // fail closed if unset
            return PackageController::constantTimeEquals(credential, expected);
        });

        let ctrl: RestController = RestController::new();
        ctrl.route("GET", "/api/v1/:group/:artifactId/:version",
            ctx => PackageController::download(ctx));
        ctrl.route("POST", "/api/v1/:group/:artifactId/:version",
            ctx => PackageController::publish(ctx)).withAuth("bearer");
        ctrl.route("GET", "/api/v1/:group/:artifactId",
            ctx => PackageController::listVersions(ctx));
        ctrl.route("GET", "/api/v1/packages",
            ctx => PackageController::catalog(ctx));

        app.register(ctrl);
        app.start();
        return 0;
    }
}
```

- `RestApi::setAuthValidator` is called directly, no magic class-name
  discovery — this was already the documented mechanism for the
  explicit-`RestApi` style (`rest.server` docs: "already possible via
  `RestApi::setAuthValidator`" as the counterpart to the annotation-only
  style's `AuthValidator` auto-discovery).
- Admin key comes from `Env::getVar` (an env var set on the host), same
  pattern as `OIDC_ISSUER`/`OIDC_JWKS_URI` elsewhere in the stdlib —
  never hardcoded/committed. Empty/unset → reject everything (fail
  closed), not silently-open.
- No timing-safe string compare exists in the stdlib (checked
  `mod-crypto`/`mod-hash`). `PackageController::constantTimeEquals` is a
  small local static helper (loop XOR-ing every byte of both strings,
  always comparing full length regardless of an early mismatch) — one
  call site doesn't justify a stdlib PR.
- `PackageController::download`/`publish`/etc. are `fnc` (static)
  methods with signature `fn(ctx: HttpContext) -> Nothing`. **Verified
  a bare method reference (`ctrl.route("GET", "/ping",
  PackageController::download)`) does NOT work** — the compiler tries to
  *call* it immediately ("expected 1 arguments, found 0" / "expected Fn,
  found Nothing") rather than treating it as a first-class function
  value. Must wrap in a lambda: `ctx => PackageController::download(ctx)`
  — verified this form compiles and dispatches correctly. Either way,
  `PackageController` itself never needs to extend anything — sidesteps
  bug #173 entirely by construction.

---

## 7. Risks / open questions — spike before committing to the architecture

This section is deliberately upfront, not an appendix — per this
project's own stated philosophy (`CLAUDE.md`: "verify against real,
independent behavior, not just self-consistent tests"; "kein
Silent-Garbage"), these are exactly the kind of assumptions that have
bitten past tinox work when taken on faith from docs instead of tested.

### 7.1 Binary (NUL-byte) safety — **CONFIRMED BROKEN, decided: base64 everywhere**

Spiked directly (`/tmp/.../nulspike/Main.tnx`, `tinox run`):

```tinox
let s: String = "AB\x00CD";
Fs::writeFile("out.bin", s);
println("wrote " + s.len().toString() + " chars");
```

Output: `wrote 2 chars` — not 5 — and `out.bin` on disk is 2 bytes (`AB`).
This isn't specific to `Fs::writeFile` (which does turn out to call
`fputs`, a `strlen`-based C write — see `fileWriteAllText` in
`runtime/runtime.c:1151`); the truncation already happened at `s.len()`,
i.e. **tinox's `String` runtime representation is itself a NUL-terminated
C string**, full stop. This is a language/runtime-level constraint, not
something fixable in `fs.tnx` or worked around in one call site.

**Decision (final, not "if it truncates" anymore): artifact bytes are
base64-encoded for both storage on disk and wire transfer**, exactly the
fallback shape from §5 (`{"filename": "...", "contentBase64": "..."}`
for POST, and the GET response also returns this JSON shape rather than
a raw-octet-stream body — a raw binary HTTP response would hit the exact
same C-string problem on the way out). `tinox.core.base64`'s
`Base64::encodeBytes`/`decodeBytes` operate on `List<Int64>` (byte
lists), not `String`, specifically to avoid this — use those, not a
`String`-typed detour, anywhere actual artifact bytes are touched.

### 7.2 issue #140 — **CONFIRMED SAFE: `RestApi` does not share the crash-prone path**

Confirmed two ways:

1. **Source reading.** `RestApi::start()` (`crates/tinox-core/rest/server/RestApi.tnx:233-237`)
   is exactly `this.registerRoutes(); this.server.listen();` where
   `server: HttpServer` — i.e. `RestApi` is a thin wrapper *around*
   `tinox.core.http_server.HttpServer`, the exact class the docs call
   out as unaffected by #140. Issue #140 is specifically about the
   compiler's *auto-generated* `main()` for purely-annotation-only
   controllers with no explicit `RestApi`/`main` in the program at all
   (a different code path, `tinox_HttpServer_listen`) — never entered as
   long as we write an explicit `Main.tnx` with `RestApi::new(...)`.
2. **Load test.** Spiked an explicit-`main()` `RestApi` server with a
   trivial allocating route (200-iteration string-concat loop per
   request, matching #140's own repro shape) — 3000 sequential requests,
   then 500 requests at 20-way concurrency. No crash, server stayed
   alive throughout both.

**Decision: use explicit `RestApi::new(port)` + manual wiring in
`Main.tnx`, never let the compiler auto-generate a `main`.** No HTTP/3
fallback needed for this reason.

### 7.2b New finding while spiking: bug #173 — don't subclass `RestController`

Tried the docs' own documented pattern (`class PackageController :
RestController { ... }`, registered via `app.register(ctrl)`) and hit a
new, reproducible typechecker bug, filed as
[subnix-work/tinox#173](https://github.com/subnix-work/tinox/issues/173):
passing a `RestController` **subclass** instance anywhere a plain
`RestController`-typed parameter/variable is expected fails ("expected
RestController, found PackageController"), even though the identical
subtype relationship works fine through a bare/free function call. Also
reproduces with zero stdlib involvement (a minimal `Animal`/`Dog`/`Zoo`
repro is in the issue).

**Workaround (validated, no subclassing needed at all):** don't extend
`RestController` — instantiate the **base class directly**
(`RestController::new()`), call `.route(method, path, handler)` on it
like a plain route builder (no polymorphism involved, exact type match),
and chain `.withAuth("bearer")` on routes that need it. Auth validation
itself is wired via `RestApi::setAuthValidator(validator)` — called
directly on the `app: RestApi` instance in `Main.tnx` — **not** the
docs' auto-discovered project-defined `AuthValidator` class mechanism
(that discovery is specifically for the *other* compilation mode, the
one we're avoiding per §7.2's decision; for explicit `RestApi` wiring the
docs already note `setAuthValidator` was "already possible"). Verified
end-to-end with a 2-route controller (one open, one
`.withAuth("bearer")`): no-auth → 401, wrong bearer → 401, correct
bearer → 201, and the open route keeps working throughout — exactly the
shape §6's `PackageController` needs (public download, authenticated
publish). §3 and §6 below are updated to this validated pattern; the
`AuthValidator.tnx` file from the original repo layout is dropped
(replaced by an inline validator function in `Main.tnx`).

*(Side note on process hygiene during this spike: an earlier round of
this same test produced bizarre, inconsistent 401-vs-404 results —
traced to leftover `tinox run` server processes from previous spike
iterations still listening on the same port and splitting requests
between them, not a tinox bug. Worth remembering next time something
looks impossibly inconsistent: check `ss -tlnp` for stale listeners
before suspecting the compiler.)*

### 7.3 No atomic file replace, no file locking

`Fs::moveFile` is documented as "copy + delete", not an OS-level atomic
rename — and there's no file-locking primitive in `tinox.core.fs`. A
crash mid-publish could leave a half-written artifact or metadata file.

Mitigated in practice by: (a) a single admin publishing sequentially by
hand, not concurrent automated publishers, and (b) if `RestApi` does turn
out to be single-threaded (see 7.2), there's no *intra-process*
concurrent-write race by construction — requests are handled one at a
time. Residual risk is only a mid-write crash, not a race. Accepting this
as a known v1 limitation rather than over-engineering write-then-rename
semantics tinox doesn't actually give a real primitive for yet — matches
"gezielt statt pauschal fixen".

### 7.4 Validation / path traversal

`group`, `artifactId`, `version` are used directly to build filesystem
paths (`data/index/<group>/<artifactId>/<version>.json`) — a value like
`../../etc` must be rejected outright, not sanitized. Use
`Validation::matchesPattern` with a strict allowlist, e.g.
`^[a-zA-Z0-9._-]+$`, applied to all three path segments before touching
the filesystem, rejecting with 400 on any character outside that set (no
`/`, no `..`, no leading dot). Reject at the very top of the handler,
before any `Fs::*` call.

---

## 8. Phased plan

**Phase 0 — spike. DONE.** §7.1/§7.2/§7.2b resolved empirically (see
those sections) — decided: base64 envelope everywhere, explicit `Main.tnx`
+ manual `RestApi` wiring, no `RestController` subclassing.

**Phase 1 — backend MVP. DONE, implemented in `registry-backend/src/`:**
`PackageMeta`/`PackageSummary`/`PublishRequest`/`ArtifactPayload` (plain
`@JsonSerializable` records), `PackageStore` (fs read/write + in-memory
catalog), `PackageController` (the four endpoints, plain class per
§7.2b), `Main.tnx` (manual `RestApi` wiring + inline auth validator).
Verified against a live instance with real `curl` requests (not just
internal tests) per this project's "verify against a real, independent
client" habit: publish a real `tar.gz`, download it back, byte-diff
against the original (identical), duplicate-version → 409, no/wrong
bearer → 401, correct bearer → 201, unknown version → 404, invalid path
segments → 400, 25 mixed rapid-fire requests against all four endpoints
with no crash.

Two bugs found and fixed *while building this*, beyond the two spiked in
Phase 0:

- **`Fs::createDirectory` (`mkdir`) is neither recursive nor does it
  surface an error if the parent doesn't exist** — it just silently does
  nothing (return value is `void`/discarded in `runtime.c`). Confirmed
  by spike: `PackageStore::create()` against a `dataDir` whose
  grandparent didn't already exist created *nothing at all*, silently,
  and every subsequent read/write against that tree failed just as
  silently — no error anywhere, just an empty catalog. Fixed by having
  `PackageStore::ensureDir` split the path and create every ancestor
  directory one level at a time from the root down, rather than trusting
  a single `Fs::createDirectory(path)` call for a possibly-multi-level
  new path.
- **Path-traversal gap in the validation regex itself**: `^[a-zA-Z0-9._-]+$`
  allows `.` and `..` (they consist entirely of allowed characters) even
  though both are filesystem-special. The live server happened to return
  404 for a `..` version segment, but only because the HTTP layer's own
  path normalization collapsed it before routing ever reached our
  validation — relying on that would have been fragile, undocumented
  upstream behavior standing in for an actual input check. Fixed by
  rejecting `s == "."`/`s == ".."` explicitly in
  `PackageStore::isValidSegment`, regardless of what the HTTP layer does
  upstream.

Not yet re-verified as a formal `tests/e2e/*.tnx` suite — the curl
verification above was manual/interactive. That's Phase 2.

**Phase 2 — hardening, in progress.**

- **Upload size cap: done, but revealed a third runtime bug,
  [subnix-work/tinox#174](https://github.com/subnix-work/tinox/issues/174).**
  The HTTP runtime hard-caps request bodies at 4 MiB (`TINOX_MAX_BODY`,
  `runtime.c:2656`, a deliberate Bug-96 DoS mitigation) but on overflow
  it **silently truncates** the body instead of rejecting the request —
  no 413, no signal to the handler. Spiked with a real 150 MB upload:
  the server returned `201 Created` with a *valid-looking* SHA-256 over
  the truncated ~3 MB prefix, no indication anything was wrong. A
  length check on `ctx.request.body`/`req.contentBase64` *after* this
  truncation already happened can't detect it — a truncated-but-still-
  large body looks like a normal request. Fixed on our side by reading
  `ctx.request.getHeader("Content-Length")` (the client's *original*
  claimed size, untouched by the runtime's clamp) and rejecting with 413
  before trusting anything else, with the post-decode size check kept as
  a second layer. Consequence: `maxArtifactBytes()`/`maxRequestBytes()`
  had to shrink to 2 MiB/3 MiB (comfortably under the runtime's 4 MiB
  ceiling) rather than the "100 MiB, generous for a package archive"
  originally planned — **this is now a real, load-bearing limitation of
  the whole registry until #174 is fixed upstream**, not just a
  configurable safety margin. Verified live: small upload still 201,
  150 MB upload now cleanly 413, nothing corrupted gets stored.
- Validation (7.4) and 409-on-duplicate-version: already done in Phase 1.
- Structured JSON error bodies: already done in Phase 1 (every error
  path returns `{"error": "..."}`, not plain text).
- **Formal e2e suite: done, but as `tests/e2e/smoke_test.sh`, not
  `tests/e2e/*.tnx`.** tinox's own `// expect:`-`.tnx` convention doesn't
  fit here: import resolution is relative-to-source-file-directory only
  (checked `resolve_imports()` in `crates/tinox/src/main.rs` — no
  project-root/`src/` fallback), and `tinox.core.process` has no
  subprocess-launch capability (only self-process control). A test living
  under `tests/e2e/` therefore has no way to reach `PackageStore`/
  `PackageController` in `../../src/`, nor to launch the compiled backend
  itself. Testing the backend as a real black-box HTTP client (curl) is
  arguably the better fit anyway — matches this project's own "verify
  against real, independent systems" preference more than an in-process
  simulation would. 15 assertions, all green: auth (401/401/201),
  server-computed sha256 matches `sha256sum` of the real artifact,
  duplicate-version 409, byte-identical download, 404, 400 on invalid
  segments, 413 + not-stored on oversized upload, server survives the
  whole run. One process-hygiene lesson learned while writing it (again):
  a truncated/piped-to-`head` test run left an orphaned server on the
  same port, causing one flaky false failure until cleaned up — the
  script now refuses to start if its port is already in use, specifically
  to catch this class of artifact going forward.

**Phase 2 — hardening.** Validation (7.4), 409-on-duplicate-version,
size cap on uploads (reject absurdly large bodies before reading them
fully — check whether `HttpRequest`/the server exposes a
content-length pre-check or whether this has to be a post-hoc size
check), structured error JSON bodies instead of plain text, a handful of
`tests/e2e/*.tnx` covering publish/download/list/duplicate-version/bad-
auth/bad-path-chars.

**Phase 3 — Quarkus + Vaadin frontend. DONE**, `registry-frontend/`:
scaffolded via the official `quarkus-maven-plugin:create` (Quarkus
3.38.1, Java 21), Vaadin 25.2.5 added via `vaadin-bom` +
`vaadin-quarkus-extension`. `RegistryClient` (plain
`java.net.http.HttpClient` + Jackson, no JAX-RS client interface needed
for four simple JSON endpoints), `PackageSummary`/`PackageMeta` DTOs
mirroring the backend's JSON shapes, `PackageListView` (`@Route("")`,
`Grid<PackageSummary>` + `TextField` search via `GridListDataView`),
`PackageDetailView` (`@Route("packages/:group/:artifact")`, versions
table + `Anchor` download links pointing straight at
`GET /api/v1/{group}/{artifactId}/{version}` — no byte-proxying through
the Java side).

Verified for real, not just "it compiles": ran the actual backend +
frontend (`quarkus:dev`) side by side, published two real versions of a
demo package, then drove a **headless Chromium (Playwright)** through
the whole flow — list view shows the right data, search box actually
filters the grid (`grid.size` 1 → 0 → 1), "View" link navigates to the
detail page, both versions show up there, and the download link's `href`
points at the correct backend URL. 8/8 checks green, zero uncaught JS
errors.

One dead-end worth recording (cost real debugging time, no actual bug):
initial verification via `grid.textContent`/`outerHTML` after typing a
filter query kept showing the filtered-out row and looked like a broken
search filter. Root cause was **the test, not the code**, two compounding
mistakes: (1) `page.keyboard.type()` alone never fires Vaadin's
`ValueChangeMode.ON_CHANGE` listener without a following blur/Enter —
so several early check runs never even triggered a filter change server-
side; (2) Vaadin Grid virtualizes and recycles row DOM nodes, so a
filtered-out row's `<vaadin-grid-cell-content>` text can still be
present in the DOM after the row is no longer "shown" — checking raw
`textContent` on the grid element is not a valid signal for what's
actually displayed. Switched to reading the grid's own `size` property
(and confirmed via the raw Vaadin client-server UIDL protocol exchange,
visible in the browser console, that the server correctly computed and
pushed `size: 0`/an empty item list) — that's what actually confirmed
the filter always worked correctly. Along the way this also motivated
swapping the initial `ListDataProvider` + `grid.setDataProvider()`
implementation for the more modern `grid.setItems()` →
`GridListDataView` API (Vaadin 24+'s recommended pattern for exactly
this search-box-over-a-Grid use case) — not because the old pattern was
proven broken, but because it's the more idiomatic choice either way.

**Phase 4 — deployment.** Reverse proxy (nginx/Caddy) in front of both
processes for TLS termination (see §2), systemd units (or containers) for
the tinox backend and the Quarkus app, `TINOX_CENTRAL_ADMIN_KEY` set as a
host-level env var/secret, not in any repo.

**Phase 5 — (separate, tracked in #172) wire `tinox add`/`tinox install`
in the main `tinox` repo to resolve against this registry.** Not part of
this repo's scope; revisit #172 once Phase 4 is live and pick a concrete
default `[registry].url`.

**Post-Phase-3 rework — backend switched to fully annotation-driven +
DI, per explicit user request** ("use as many annotations as possible,
it's more elegant"). Supersedes §7.2/§7.2b's earlier decision to avoid
the auto-generated-main compilation mode — that decision was made to
dodge issue #140 (GC crash), which turned out to already be fixed
(closed 2026-08-02, three days *before* the original §7.2 spike — a
stale-information mistake on the assistant's part; CLAUDE.md's own
"check whether a later fix already closed this" lesson, learned the
hard way here instead of applied proactively). Current architecture:

- `Main.tnx` **removed entirely** — no explicit `RestApi`/`main`
  anywhere in `registry-backend/src/`, so the compiler auto-generates
  both. Entry point for `tinox run`/`tinox build` is now
  `PackageController.tnx` (needs to be the file passed, since its import
  graph is what pulls in `AuthValidator`/`PackageStore`/the DTOs).
- `PackageController`: `@ApplicationComponent` + `@Path("/api/v1")` at
  class level, `@Inject var store: PackageStore` field, each endpoint a
  `@GET`/`@POST` + `@Path` + `@Produces("application/json")` (and
  `@Consumes`/`@Auth("bearer")` on `publish`) annotated method taking
  just `ctx: HttpContext` — no more manually-passed `store` parameter or
  closures in `Main.tnx`.
- `PackageStore`: `@ApplicationComponent` too. DI-created instances are
  **zero-allocated, no constructor ever runs** (confirmed via
  `crates/tinox-codegen/src/codegen.rs`'s `emit_di_code` — `{name}_di_get()`
  always goes through `tinox_alloc` directly, no user `fn new()` call,
  for both `@ApplicationComponent` and `@Startup`) — so the old
  `PackageStore::create(dataDir)` factory constructor is gone, replaced
  by a `this.ensureInit()` lazy-init guard (same pattern
  `examples/http3_rest_api/src/TaskController.tnx` already uses for
  exactly this reason) called at the top of every public method. Watch
  for the recursion trap this caused once: `rebuildCatalog()`/
  `updateCatalog()` run *from inside* `ensureInit()` itself and must call
  the un-guarded `readVersionsFromDisk()` helper, never the public
  `ensureInit()`-guarded `listVersions()` — that recurses forever
  otherwise.
- `AuthValidator.tnx`: new file, same `fnc validate(authType, credential)
  -> Bool` shape as before, but now discovered *by class name* by the
  compiler for `@Auth`-protected annotated routes (not wired via
  `RestApi::setAuthValidator` anymore, since there's no `RestApi`
  instance in this architecture at all). **Must still be explicitly
  imported** from wherever the entry point's import graph is rooted
  (confirmed by spike: silently rejects every `@Auth` request — same
  fail-closed default as always — if the class exists in the project but
  was never pulled into the compiled program via an `import`) — added
  `import AuthValidator;` to `PackageController.tnx` for exactly this
  reason.
- `@Produces`/`@Consumes` take a **plain MIME-type string**
  (`"application/json"`), not `MediaType::Json` — the enum-qualified form
  parses fine as ordinary code but is rejected as an annotation argument
  ("expected identifier" at the `::`), confirmed by a failed compile
  attempt.

**Two new bugs found switching over, one accepted, one found-and-fixed:**

- **subnix-work/tinox#175** (accepted, not fixed): under heavy
  concurrent load, the annotation-driven server drops ~1-2% of
  connections (`CURLE_RECV_ERROR`) that the manually-wired
  `HttpServer`/`RestApi` path (0 failures, identical load) doesn't. Not
  a crash — the process survives — just a small, real reliability gap.
  User's explicit call: proceed anyway, revisit if it becomes a problem
  in practice.
- **subnix-work/tinox#176** (found AND fixed, by the assistant, in the
  tinox compiler itself — user's explicit request: "fix the bug before
  you continue" rather than route around it). Root cause: `runtime.c`'s
  `route_matches()` (backing `tinox_HttpServer_get`/etc., the C-level
  matcher for annotation-driven routes only — the separate pure-Tinox
  `HttpServer`/`RouteMatcher.tnx` path was never affected) parsed each
  `:param` name into a stack buffer *reused every loop iteration*, then
  handed that pointer to `tinox_map_set` against a `borrowed_keys=1` map
  (`g_path_params_map`) — which stores the raw pointer without copying
  for such maps. Every route with more than one `:param` ended up with
  every parameter's map key pointing at the same reused stack slot,
  correct only for whichever parameter was parsed *last*; every earlier
  one silently returned `""` from `getParam()`. Fixed by heap-allocating
  the name buffer (mirroring how the adjacent `val` buffer was already
  heap-allocated) — 4-line change, verified against the original repro
  plus this registry's full multi-param API and its whole smoke-test
  suite (15/15 green). This is exactly why routes like
  `/api/v1/:group/:artifactId/:version` (3 params) silently 400'd with
  empty group/artifactId right after the switch — caught before it ever
  shipped anywhere real.

Net effect: the annotation-driven rewrite is now verified working
end-to-end (same 15/15 smoke-test suite, full publish/download/list/
catalog/auth/validation/size-cap flow) with the two known/accepted risks
above being the *only* remaining differences from the previous explicit-
`main()` architecture.

---

## 9. Open decisions still needing you

- Domain/hosting for the public instance (affects TLS cert issuance,
  DNS — not something to guess).
- Whether the admin key is a single static long-lived secret (v1,
  simplest) or something rotate-able later — fine to punt, `Env::getVar`
  doesn't care either way.
- Confirm repo split is desired as planned (`registry-backend/` +
  `registry-frontend/` in this one repo) vs. two separate repos — kept
  as one repo here since they share a release cadence and a single
  `PLAN.md`/issue tracker is simpler for a two-component v1, but this is
  a call you might feel differently about once Phase 3 needs its own
  Maven/CI pipeline distinct from `make check`-style tinox tooling.
