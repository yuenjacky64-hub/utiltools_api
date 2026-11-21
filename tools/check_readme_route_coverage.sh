#!/usr/bin/env bash
#
# check_readme_route_coverage.sh - README <-> controller route-coverage probe (UTILTOOLS-138).
#
# Reads two sources of truth and compares them as SETS, deriving both sides on every run:
#   * shipped side: every handler mapping under src/main/java/com/example/utiltools/**/*Controller.java
#                   (class-level @RequestMapping prefix + @Get/Post/Put/Patch/DeleteMapping, bare
#                   annotations resolved to the class prefix, `@RequestMapping(method = RequestMethod.X)`
#                   treated identically);
#   * README side:  every row of the `## API Endpoints Reference` table matching
#                   `| `METHOD` | `/path` |` (rows outside that table section are ignored, so prose
#                   mentions elsewhere in README.md cannot mask a missing or dead row).
#
# Modes
#   --routes       REQ-01: exit 1 with `FAIL: missing route <METHOD> <PATH> (<src>:<line>)` when a
#                  shipped mapping has no README row.
#   --unknown      REQ-02: exit 1 with `FAIL: unknown route <METHOD> <PATH> (README.md:<line>)` when a
#                  README row has no shipped mapping (set equality, not superset).
#   --test-counts  REQ-04: exit 1 with
#                  `FAIL: test count mismatch badge=<n> prose=<n> actual=<n>` when the README badge
#                  (`Tests-<n>`) or the prose (`<n> unit and integration tests`) disagrees with the
#                  counted `@Test` methods under src/test/java.
#   --self-test    REQ-03: proves the probe can fail closed - on a scratch README copy with one row
#                  deleted `--routes` must report `FAIL: missing route `, with one phantom row added
#                  `--unknown` must report `FAIL: unknown route `, and the pristine copy must exit 0.
#                  Exit 1 with `FAIL: self-test <case>` when a case does not fire.
#   --all          --routes --unknown --test-counts (the default when no mode is given).
#   --readme PATH  Point the probe at a different README (used by --self-test fixtures; defaults to
#                  $README, else ./README.md).
#
# Failure contract: every diagnostic starts with its pinned literal and carries a `path:line`
# citation. Exit codes: 0 = clean, 1 = drift/self-test failure, 2 = usage error.
#
# Read-only: the script never writes inside the repo; scratch files live in a mktemp -d cleaned by trap.
set -uo pipefail

cd "$(dirname "$0")/.." || exit 2

SRC_MAIN="src/main/java/com/example/utiltools"
SRC_TEST="src/test/java/com/example/utiltools"
README="${README:-README.md}"
TABLE_HEADING='API Endpoints Reference'

DO_ROUTES=0
DO_UNKNOWN=0
DO_TEST_COUNTS=0
DO_SELF_TEST=0

usage() {
    echo "usage: $0 [--routes] [--unknown] [--test-counts] [--self-test] [--all] [--readme PATH]" >&2
    exit 2
}

while [ $# -gt 0 ]; do
    case "$1" in
        --routes)      DO_ROUTES=1 ;;
        --unknown)     DO_UNKNOWN=1 ;;
        --test-counts) DO_TEST_COUNTS=1 ;;
        --self-test)   DO_SELF_TEST=1 ;;
        --all)         DO_ROUTES=1; DO_UNKNOWN=1; DO_TEST_COUNTS=1 ;;
        --readme)
            [ $# -ge 2 ] || usage
            README="$2"; shift ;;
        --readme=*)    README="${1#--readme=}" ;;
        -h|--help)     usage ;;
        *)             usage ;;
    esac
    shift
done

if [ "$DO_ROUTES$DO_UNKNOWN$DO_TEST_COUNTS$DO_SELF_TEST" = "0000" ]; then
    DO_ROUTES=1; DO_UNKNOWN=1; DO_TEST_COUNTS=1
fi

TMPDIR_PROBE="$(mktemp -d)"
trap 'rm -rf "$TMPDIR_PROBE"' EXIT INT TERM

# --- shipped side ------------------------------------------------------------------------------
# Emits `METHOD PATH src/file.java:line`, one per handler mapping. Both sides of the comparison are
# derived at run time (INV-01): there is no expected-route list and no pinned total.
derive_routes() {
    local f
    find "$SRC_MAIN" -name '*Controller.java' 2>/dev/null | sort | while IFS= read -r f; do
        awk -v file="$f" '
            function emit(meth, path) {
                gsub(/\/+/, "/", path)
                printf "%s %s %s:%d\n", toupper(meth), path, file, FNR
            }
            {
                line = $0
                if (line ~ /^@RequestMapping\(/) {                      # class-level prefix
                    if (match(line, /"[^"]*"/)) {
                        prefix = substr(line, RSTART + 1, RLENGTH - 2)
                    }
                    next
                }
                if (line ~ /^[ \t]+@(Get|Post|Put|Patch|Delete)Mapping([ \t]*\(|[ \t]*$)/) {
                    verb = line
                    sub(/^[ \t]+@/, "", verb)
                    sub(/Mapping.*$/, "", verb)
                    path = ""
                    if (match(line, /"[^"]*"/)) {
                        path = substr(line, RSTART + 1, RLENGTH - 2)
                    }
                    if (path == "")            { emit(verb, prefix) }
                    else if (path ~ /^\//)     { emit(verb, prefix path) }
                    else                       { emit(verb, prefix "/" path) }
                    next
                }
                if (line ~ /^[ \t]+@RequestMapping\(/ && line ~ /method[ \t]*=[ \t]*RequestMethod\./) {
                    verb = line
                    sub(/.*RequestMethod\./, "", verb)
                    sub(/[^A-Za-z].*$/, "", verb)
                    path = ""
                    if (match(line, /"[^"]*"/)) {
                        path = substr(line, RSTART + 1, RLENGTH - 2)
                    }
                    if (path == "")            { emit(verb, prefix) }
                    else if (path ~ /^\//)     { emit(verb, prefix path) }
                    else                       { emit(verb, prefix "/" path) }
                    next
                }
            }
        ' "$f"
    done
}

# --- README side -------------------------------------------------------------------------------
# Emits `METHOD PATH README.md:line` for rows inside the `## ... API Endpoints Reference` table only.
parse_readme_rows() {
    local readme="$1"
    awk -v file="$readme" -v heading="$TABLE_HEADING" '
        function trim(s) { gsub(/^[ \t]+|[ \t]+$/, "", s); return s }
        {
            if (!in_table) {
                if ($0 ~ /^## / && index($0, heading) > 0) { in_table = 1 }
                next
            }
            if ($0 ~ /^## / || $0 ~ /^---[ \t]*$/) { in_table = 0; next }
            row = trim($0)
            if (row ~ /^\| `(GET|POST|PUT|PATCH|DELETE)` \| `\/[^`]+` \|/) {
                meth = row
                sub(/^\|[ \t]*`/, "", meth)
                sub(/`.*$/, "", meth)
                path = row
                sub(/^\|[ \t]*`[A-Z]+`[ \t]*\|[ \t]*`/, "", path)
                sub(/`.*$/, "", path)
                printf "%s %s %s:%d\n", meth, path, file, FNR
            }
        }
    ' "$readme"
}

# --- checks ------------------------------------------------------------------------------------
emit_set_diff() {  # <left-file> <right-file> <left-label-by-key>
    local left="$1" right="$2" label="$3"
    cut -d' ' -f1-2 "$left" | sort -u > "$TMPDIR_PROBE/left.keys"
    cut -d' ' -f1-2 "$right" | sort -u > "$TMPDIR_PROBE/right.keys"
    [ "$label" = "left" ] || true
    comm -23 "$TMPDIR_PROBE/left.keys" "$TMPDIR_PROBE/right.keys"
}

check_routes() {  # REQ-01
    local readme="$1" missing rc=0
    derive_routes > "$TMPDIR_PROBE/shipped.txt"
    parse_readme_rows "$readme" > "$TMPDIR_PROBE/rows.txt"
    missing="$(emit_set_diff "$TMPDIR_PROBE/shipped.txt" "$TMPDIR_PROBE/rows.txt" left)"
    if [ -n "$missing" ]; then
        rc=1
        while IFS= read -r key; do
            awk -v k="$key" '
                { key = $1 " " $2; if (key == k) { printf "FAIL: missing route %s %s (%s)\n", $1, $2, $3; exit } }
            ' "$TMPDIR_PROBE/shipped.txt"
        done <<< "$missing"
    fi
    return "$rc"
}

check_unknown() {  # REQ-02
    local readme="$1" extra rc=0
    derive_routes > "$TMPDIR_PROBE/shipped.txt"
    parse_readme_rows "$readme" > "$TMPDIR_PROBE/rows.txt"
    extra="$(emit_set_diff "$TMPDIR_PROBE/rows.txt" "$TMPDIR_PROBE/shipped.txt" left)"
    if [ -n "$extra" ]; then
        rc=1
        while IFS= read -r key; do
            awk -v k="$key" '
                { key = $1 " " $2; if (key == k) { printf "FAIL: unknown route %s %s (%s)\n", $1, $2, $3; exit } }
            ' "$TMPDIR_PROBE/rows.txt"
        done <<< "$extra"
    fi
    return "$rc"
}

check_test_counts() {  # REQ-04
    local readme="$1" badge prose actual badge_line prose_line
    badge="$(grep -oE 'badge/Tests-[0-9]+' "$readme" | head -1 | grep -oE '[0-9]+')"
    badge_line="$(grep -nE 'badge/Tests-[0-9]+' "$readme" | head -1 | cut -d: -f1)"
    prose="$(grep -oE '[0-9]+ unit and integration tests' "$readme" | head -1 | grep -oE '^[0-9]+')"
    prose_line="$(grep -nE '[0-9]+ unit and integration tests' "$readme" | head -1 | cut -d: -f1)"
    actual="$(grep -rhoE '^[[:space:]]*@Test\b' "$SRC_TEST" --include='*.java' 2>/dev/null | wc -l | tr -d ' ')"

    badge="${badge:-none}"; prose="${prose:-none}"
    if [ "$badge" != "$actual" ] || [ "$prose" != "$actual" ]; then
        printf 'FAIL: test count mismatch badge=%s prose=%s actual=%s (%s:%s, %s:%s)\n' \
            "$badge" "$prose" "$actual" \
            "$readme" "${badge_line:-?}" "$readme" "${prose_line:-?}"
        return 1
    fi
    return 0
}

# --- self-test (REQ-03) ------------------------------------------------------------------------
self_test() {
    local fixture="$TMPDIR_PROBE/README.fixture" first_row_line out rc

    cp "$README" "$fixture" || { echo "FAIL: self-test cannot copy $README"; return 1; }

    # case 1: one existing row deleted -> --routes must report a missing route.
    first_row_line="$(parse_readme_rows "$fixture" | head -1 | awk -F: '{print $NF}')"
    if [ -z "${first_row_line:-}" ]; then
        echo "FAIL: self-test no table row found in $README"
        return 1
    fi
    awk -v drop="$first_row_line" 'FNR != drop' "$fixture" > "$TMPDIR_PROBE/README.deleted"
    out="$(check_routes "$TMPDIR_PROBE/README.deleted")"; rc=$?
    if [ "$rc" -ne 1 ] || ! printf '%s' "$out" | grep -q 'FAIL: missing route '; then
        echo "FAIL: self-test deleted-row case did not fire (rc=$rc)"
        return 1
    fi

    # case 2: one phantom row injected -> --unknown must report an unknown route.
    awk -v heading="$TABLE_HEADING" '
        { print }
        !done && $0 ~ /^## / && index($0, heading) > 0 { in_table = 1; next }
        in_table && !done && $0 ~ /^\| :---/ {
            print "| `GET` | `/api/v1/utils/self-test/phantom` | self-test phantom row |"
            done = 1
        }
    ' "$fixture" > "$TMPDIR_PROBE/README.phantom"
    out="$(check_unknown "$TMPDIR_PROBE/README.phantom")"; rc=$?
    if [ "$rc" -ne 1 ] || ! printf '%s' "$out" | grep -q 'FAIL: unknown route '; then
        echo "FAIL: self-test phantom-row case did not fire (rc=$rc)"
        return 1
    fi

    # case 3: the pristine copy must stay clean.
    out="$(check_routes "$README")"; rc=$?
    if [ "$rc" -ne 0 ]; then
        echo "FAIL: self-test pristine README is not clean (rc=$rc)"
        printf '%s\n' "$out"
        return 1
    fi
    return 0
}

# --- main --------------------------------------------------------------------------------------
status=0

if [ "$DO_ROUTES" -eq 1 ]; then
    if check_routes "$README"; then
        echo "PASS: every shipped handler mapping has a README row"
    else
        status=1
    fi
fi
if [ "$DO_UNKNOWN" -eq 1 ]; then
    if check_unknown "$README"; then
        echo "PASS: no README row lacks a shipped handler mapping"
    else
        status=1
    fi
fi
if [ "$DO_TEST_COUNTS" -eq 1 ]; then
    if check_test_counts "$README"; then
        echo "PASS: README test counts match the counted @Test methods"
    else
        status=1
    fi
fi
if [ "$DO_SELF_TEST" -eq 1 ]; then
    self_test || status=1
fi

exit "$status"
