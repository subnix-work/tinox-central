#!/usr/bin/env bash
# End-to-end smoke test for the registry backend, exercised as a real
# black-box HTTP client (curl) against a live `tinox run` instance --
# not a tinox-internal `.tnx`/`spawn`-based simulation.
#
# Why a shell script and not tests/e2e/*.tnx with `// expect:` (the
# convention used by the tinox compiler repo itself): tinox's import
# resolution is relative-to-source-file-directory only (no project-root/
# src/ fallback, confirmed by reading resolve_imports() in
# crates/tinox/src/main.rs) and tinox.core.process has no
# subprocess-launch capability (only self-process control: exit/sleep/
# pid/args/env) -- so a .tnx test living under tests/e2e/ has no way to
# either import PackageStore/PackageController from ../../src/ or launch
# the compiled backend as a child process itself. Testing it as a real,
# independent HTTP client is arguably the better fit anyway (matches
# this project's own stated preference for verifying against real
# systems, not just self-consistent simulations).
#
# Usage: ./smoke_test.sh (from anywhere; paths below are script-relative)
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC_DIR="$SCRIPT_DIR/../../src"
WORKDIR="$(mktemp -d)"
DATA_DIR="$WORKDIR/data"
PORT=8080
BASE="http://localhost:$PORT/api/v1"
ADMIN_KEY="smoke-test-admin-key"
FAILURES=0
SERVER_PID=""

pass() { echo "PASS: $1"; }
fail() { echo "FAIL: $1"; FAILURES=$((FAILURES + 1)); }

assert_status() {
    local desc="$1" expected="$2" actual="$3"
    if [ "$actual" = "$expected" ]; then
        pass "$desc (HTTP $actual)"
    else
        fail "$desc (expected HTTP $expected, got $actual)"
    fi
}

cleanup() {
    if [ -n "$SERVER_PID" ] && kill -0 "$SERVER_PID" 2>/dev/null; then
        kill -9 "$SERVER_PID" 2>/dev/null
    fi
    pkill -9 -f "\.tinox_tmp.*$WORKDIR" 2>/dev/null
    rm -rf "$WORKDIR"
}
trap cleanup EXIT

if ss -tln 2>/dev/null | grep -q ":$PORT "; then
    echo "FAIL: port $PORT is already in use by another process -- refusing to start (kill stale tinox/tinox_tmp processes first)" >&2
    exit 1
fi

echo "=== starting backend (data dir: $DATA_DIR) ==="
(
    cd "$SRC_DIR" || exit 1
    TINOX_CENTRAL_DATA_DIR="$DATA_DIR" TINOX_CENTRAL_ADMIN_KEY="$ADMIN_KEY" \
        tinox run PackageController.tnx > "$WORKDIR/server.log" 2>&1 &
    echo $! > "$WORKDIR/server.pid"
    wait
) &
sleep 3
SERVER_PID="$(cat "$WORKDIR/server.pid" 2>/dev/null || true)"

if ! curl -s -o /dev/null "$BASE/packages"; then
    fail "server did not come up (see $WORKDIR/server.log)"
    cat "$WORKDIR/server.log" 2>/dev/null
    exit 1
fi
pass "server came up"

# --- build a real, non-trivial artifact ---
mkdir -p "$WORKDIR/pkgsrc"
echo "smoke test package content" > "$WORKDIR/pkgsrc/hello.tnx"
tar -C "$WORKDIR/pkgsrc" -czf "$WORKDIR/mylib-1.0.0.tar.gz" hello.tnx
ORIGINAL_SHA256="$(sha256sum "$WORKDIR/mylib-1.0.0.tar.gz" | cut -d' ' -f1)"
B64="$(base64 -w0 "$WORKDIR/mylib-1.0.0.tar.gz")"
jq -n --arg fn "mylib-1.0.0.tar.gz" --arg content "$B64" \
    '{filename: $fn, contentBase64: $content}' > "$WORKDIR/publish_body.json"

publish() {
    local auth_header="$1" version="$2"
    curl -s -o "$WORKDIR/resp.json" -w "%{http_code}" -X POST \
        ${auth_header:+-H "Authorization: $auth_header"} \
        -H "Content-Type: application/json" \
        -d @"$WORKDIR/publish_body.json" \
        "$BASE/com.example/mylib/$version"
}

echo "=== auth enforcement ==="
assert_status "publish without auth rejected" 401 "$(publish "" 1.0.0)"
assert_status "publish with wrong bearer rejected" 401 "$(publish "Bearer wrong-key" 1.0.0)"
assert_status "publish with correct bearer accepted" 201 "$(publish "Bearer $ADMIN_KEY" 1.0.0)"

REPORTED_SHA256="$(jq -r '.sha256' "$WORKDIR/resp.json")"
if [ "$REPORTED_SHA256" = "$ORIGINAL_SHA256" ]; then
    pass "server-computed sha256 matches the real artifact"
else
    fail "sha256 mismatch: server=$REPORTED_SHA256 real=$ORIGINAL_SHA256"
fi

echo "=== duplicate version ==="
assert_status "re-publishing the same version is rejected" 409 "$(publish "Bearer $ADMIN_KEY" 1.0.0)"

echo "=== download + byte-for-byte integrity ==="
DL_CODE="$(curl -s -o "$WORKDIR/download.json" -w "%{http_code}" "$BASE/com.example/mylib/1.0.0")"
assert_status "download succeeds" 200 "$DL_CODE"
jq -r '.contentBase64' "$WORKDIR/download.json" | base64 -d > "$WORKDIR/downloaded.tar.gz"
if cmp -s "$WORKDIR/mylib-1.0.0.tar.gz" "$WORKDIR/downloaded.tar.gz"; then
    pass "downloaded artifact is byte-identical to the original"
else
    fail "downloaded artifact DIFFERS from the original (corruption)"
fi

echo "=== not-found handling ==="
assert_status "unknown version returns 404" 404 \
    "$(curl -s -o /dev/null -w "%{http_code}" "$BASE/com.example/mylib/9.9.9")"

echo "=== input validation ==="
assert_status "invalid path segment (space) rejected" 400 \
    "$(curl -s -o /dev/null -w "%{http_code}" "$BASE/com%20example/mylib/1.0.0")"

echo "=== listing endpoints ==="
VERSIONS_JSON="$(curl -s "$BASE/com.example/mylib")"
if [ "$(echo "$VERSIONS_JSON" | jq 'length')" = "1" ]; then
    pass "list-versions returns exactly 1 entry"
else
    fail "list-versions returned unexpected content: $VERSIONS_JSON"
fi

CATALOG_JSON="$(curl -s "$BASE/packages")"
if [ "$(echo "$CATALOG_JSON" | jq 'length')" = "1" ]; then
    pass "catalog returns exactly 1 package"
else
    fail "catalog returned unexpected content: $CATALOG_JSON"
fi

echo "=== upload size cap (subnix-work/tinox#174 mitigation) ==="
python3 -c "import base64,sys; sys.stdout.write(base64.b64encode(b'x' * (5*1024*1024)).decode())" > "$WORKDIR/big_b64.txt"
jq -Rn --arg fn "big.bin" --rawfile content "$WORKDIR/big_b64.txt" \
    '{filename: $fn, contentBase64: $content}' > "$WORKDIR/big_body.json"
BIG_CODE="$(curl -s -o /dev/null -w "%{http_code}" -X POST -H "Authorization: Bearer $ADMIN_KEY" \
    -H "Content-Type: application/json" -d @"$WORKDIR/big_body.json" "$BASE/com.example/biglib/1.0.0")"
assert_status "oversized upload rejected with 413" 413 "$BIG_CODE"
assert_status "oversized upload was NOT stored" 404 \
    "$(curl -s -o /dev/null -w "%{http_code}" "$BASE/com.example/biglib/1.0.0")"

echo "=== server survived the full run ==="
if kill -0 "$SERVER_PID" 2>/dev/null; then
    pass "server still alive"
else
    fail "server died during the test run"
fi

echo
echo "=== $FAILURES failure(s) ==="
exit "$FAILURES"
