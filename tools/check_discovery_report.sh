#!/usr/bin/env bash
# tools/check_discovery_report.sh — machine gate for the UTILTOOLS-42 discovery report.
#
# The report is a *document*, so every Definition of Done row of
# docs/superpowers/plans/2026-11-07-utiltools-next-implementation-task.md is checked here as a
# qa-probe: this script is the probe. It is read-only against the repo and exits 0 when every
# requested check passes, 1 with a single `FAIL: <reason>` line otherwise.
#
# Usage:
#   tools/check_discovery_report.sh [--sections] [--packages] [--inventory] [--paths]
#                                   [--evidence] [--gaps] [--selection] [--not-implemented]
#                                   [--no-src-changes] [--all] [--report PATH]
#
# Flags (each maps to one DoD row):
#   --packages        DOD-01: exactly ten `### PKG-<name>` blocks, one per real package
#                     directory, each citing a `- path:` that exists on disk and declaring
#                     `- role:`, `- endpoints:` and `- shared-dep:`; plus the §1 paragraph that
#                     names the request pipeline in plan order.
#   --sections        DOD-05: the seven literal H2 headings of plan §4.1 exist in order, the
#                     report carries no scaffold placeholder and no bare backlog marker.
#   --inventory       DOD-03/DOD-04: one `- inventory:` line with the five measured integers of
#                     plan §4.2, one resolving `- entry-point:`, an `- evidence:`/`- exit:`/
#                     `- output:` triple that re-runs, and no bare count sentence (INV-01) in §1/§2.
#   --paths           DOD-06: at least 25 distinct `- path:` citations (duplicates collapse, canonicalized with
#                     realpath so `./x`, `x` and `a/../x` count once); each failure prints `MISSING: <path>` and
#                     the run ends with a `FAIL:` line. An absolute citation must resolve inside
#                     the repo root, so a foreign path cannot pad the 25-path threshold.
#   --evidence        DOD-07: at least five `### GAP-` blocks, each with `- evidence:`,
#                     `- exit:` (integer) and `- output:` (<=160 chars, a verbatim substring of
#                     the re-run `- evidence:` output).
#   --gaps            DOD-08: every GAP block declares all three filters (F1/F2/F3), a tier in
#                     {S,M,L} and an integer score in 0..14 whose sub-scores sum to it.
#   --selection       DOD-09: exactly one `### SELECTED:` block, pointing at a GAP block, with
#                     scope, rationale, >=1 proposed-req, >=2 proposed-dod rows whose
#                     verification mode is runnable today, and >=1 selected-identifier.
#   --not-implemented DOD-10: every `- selected-identifier:` regex has zero matches in
#                     src/main and README.md (the selected task is not already implemented).
#   --no-src-changes  DOD-11: no change under src/main, src/test, README.md, pom.xml or .ait/ —
#                     neither in the working tree nor committed on this branch.
#
# Failure contract: plan §4.10 pins one exact error substring per condition, so every diagnostic
# below starts with its pinned literal (`FAIL: inventory <key>`, `FAIL: bare count claim`,
# `FAIL: absolute '- path:' outside repo`, `FAIL: '- output:' too long`,
# `FAIL: score sub-scores do not sum`) and only then adds measured detail. Assert on the literal,
# not the suffix.
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1

# A pre-set REPORT lets a fixture guard (DOD-12) point the same checks at a scratch copy.
REPORT="${REPORT:-docs/superpowers/plans/2026-11-07-utiltools-next-implementation-task.md}"
SRC_MAIN="src/main/java/com/example/utiltools"
SRC_TEST="src/test/java/com/example/utiltools"
PACKAGES=(audit common config crypto datetime jobs json ratelimit resilience text)
MIN_BYTES=6000
MIN_PATHS=25
MIN_GAPS=5
MIN_DOD=2
MAX_OUTPUT=160
# INV-01 / R-05 (DOD-04): a bare count of a tree entity is a prose claim, not a measurement.
# Beyond the two sentences plan §4.10 pins verbatim (`exactly N … sources`, `mirrors N of them`),
# any `<int> [qualifier…] <count-noun>` shape counts — `holds 10 package directories` is the
# shape that exited 0 before this pattern was widened. Lines whose integers are measured by
# construction (`- inventory:`, `- output:`) are exempt: DOD-04 names exactly those two carriers.
BARE_COUNT_ERE='(exactly|precisely)[[:space:]]+\*{0,2}[0-9]+'
BARE_COUNT_ERE="$BARE_COUNT_ERE"'|mirrors?[[:space:]]+[^.]*of them'
BARE_COUNT_ERE="$BARE_COUNT_ERE"'|[0-9]+[[:space:]]+([a-zA-Z][a-zA-Z.-]*[[:space:]]+){0,3}(packages|package[[:space:]]+director(y|ies)|directories|dirs|sources|files)\b'

RUN_SECTIONS=0 RUN_PACKAGES=0 RUN_INVENTORY=0 RUN_PATHS=0 RUN_EVIDENCE=0
RUN_GAPS=0 RUN_SELECTION=0 RUN_NOT_IMPL=0 RUN_NO_SRC=0

# §4.10 of the plan is an ordered table of ten conditions, each with an *exact* error substring;
# these are those literals, emitted as the prefix of the single `FAIL:` line (`fail_pinned` below
# appends measured detail after the pinned literal). A consumer is allowed to assert on them, so
# they must not drift back into free-form prose.
PINNED_INVENTORY_KEY='inventory'
PINNED_BARE_COUNT='bare count claim'
PINNED_ABS_PATH="absolute '- path:' outside repo"
PINNED_OUTPUT_LEN="'- output:' too long"
PINNED_SCORE_SUM='score sub-scores do not sum'

fail() { printf 'FAIL: %s\n' "$1"; exit 1; }
# `fail_pinned <§4.10 literal> [<detail>…]` — the literal comes first, verbatim, so the pinned
# substring of plan §4.10 is always present in the emitted line.
fail_pinned() {
  local literal="$1"; shift
  if [ $# -gt 0 ]; then printf 'FAIL: %s (%s)\n' "$literal" "$*"
  else printf 'FAIL: %s\n' "$literal"; fi
  exit 1
}
usage() {
  sed -n '9,16p' "$0" | sed 's/^# //'
  exit 2
}

while [ $# -gt 0 ]; do
  case "$1" in
    --sections)        RUN_SECTIONS=1 ;;
    --packages)        RUN_PACKAGES=1 ;;
    --inventory)       RUN_INVENTORY=1 ;;
    --paths)           RUN_PATHS=1 ;;
    --evidence)        RUN_EVIDENCE=1 ;;
    --gaps)            RUN_GAPS=1 ;;
    --selection)       RUN_SELECTION=1 ;;
    --not-implemented) RUN_NOT_IMPL=1 ;;
    --no-src-changes)  RUN_NO_SRC=1 ;;
    --all)
      RUN_SECTIONS=1 RUN_PACKAGES=1 RUN_INVENTORY=1 RUN_PATHS=1 RUN_EVIDENCE=1
      RUN_GAPS=1 RUN_SELECTION=1 RUN_NOT_IMPL=1 RUN_NO_SRC=1 ;;
    --report)          shift; [ $# -gt 0 ] || usage; REPORT="$1" ;;
    -h|--help)         usage ;;
    *)                 printf 'usage error: unknown flag %s\n' "$1"; usage ;;
  esac
  shift
done

if [ $((RUN_SECTIONS + RUN_PACKAGES + RUN_INVENTORY + RUN_PATHS + RUN_EVIDENCE + RUN_GAPS \
        + RUN_SELECTION + RUN_NOT_IMPL + RUN_NO_SRC)) -eq 0 ]; then
  usage
fi

TOP="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
REPORT_ABS="$REPORT"
case "$REPORT_ABS" in /*) ;; *) REPORT_ABS="$TOP/$REPORT" ;; esac

[ -f "$REPORT_ABS" ] || fail "report missing: $REPORT"
BYTES=$(wc -c < "$REPORT_ABS" | tr -d ' ')
[ "$BYTES" -ge "$MIN_BYTES" ] || fail "report too thin for a real discovery report: $BYTES bytes < $MIN_BYTES"

# Absolute, lexically normalized form of a citation: repo-relative paths are anchored at $TOP so
# `./x`, `x` and `a/../x` compare equal (realpath -m when available, a bash fallback otherwise).
absolute_path() {
  case "$1" in /*) ;; *) set -- "$TOP/$1" ;; esac
  if command -v realpath >/dev/null 2>&1; then
    realpath -m -- "$1"
    return 0
  fi
  local seg out=""
  local IFS=/
  for seg in $1; do
    case "$seg" in
      ''|.) ;;
      ..) out="${out%/*}" ;;
      *) out="$out/$seg" ;;
    esac
  done
  printf '%s\n' "${out:-/}"
}

# Lines of a `### `-headed block, heading included, up to the next `### ` heading.
block_of() {
  awk -v pat="$1" 'p && /^### /{p=0} $0 ~ pat {p=1} p' "$REPORT_ABS"
}
# Value of a `- key:` field with the markdown ticks stripped (report fields quote commands and
# outputs in backticks so they render as code).
untick() { printf '%s' "$1" | sed -E 's/^`+//; s/`+$//'; }
gap_headings() {
  grep -nE '^### GAP-[0-9]+' "$REPORT_ABS" | sed -E 's/^([0-9]+):### (GAP-[0-9]+).*/\2/'
}
# First line number whose text matches ERE $1, ignoring anything inside a fenced code block:
# a heading that only exists inside an example fence is documentation, not a section.
# ENVIRON (not -v) is used so the pattern's backslashes are not escape-processed by awk.
section_line() {
  SECTION_PAT="$1" awk '
    /^[[:space:]]*```/ { infence = !infence; next }
    !infence && $0 ~ ENVIRON["SECTION_PAT"] { print NR; exit }
  ' "$REPORT_ABS"
}

# ---------------------------------------------------------------- DOD-05
if [ "$RUN_SECTIONS" -eq 1 ]; then
  sections=(
    '^## 1\. Architecture inventory'
    '^## 2\. Convention catalogue'
    '^## 3\. Gap register'
    '^## 4\. Selection gate'
    '^## 5\. Selected implementation task'
    '^## 6\. Downstream handoff'
    '^## 7\. Path appendix'
  )
  prev=0
  for pat in "${sections[@]}"; do
    lineno=$(section_line "$pat")
    [ -n "${lineno:-}" ] || fail "section missing: ${pat#^## }"
    [ "$lineno" -gt "$prev" ] || fail "section out of order: ${pat#^## } (line $lineno after $prev)"
    prev=$lineno
  done
  [ -n "$(section_line '^### 3\.1 TODO sweep')" ] || fail "section missing: ### 3.1 TODO sweep"
  [ -n "$(section_line '^### 3\.2 Gap register')" ] || fail "section missing: ### 3.2 Gap register"
  # Scaffold placeholders are always fatal.
  if grep -qE '\[unfilled\]|\[paste output verbatim\]' "$REPORT_ABS"; then
    fail "scaffold placeholder left in report ([unfilled] / [paste output verbatim])"
  fi
  # Bare backlog markers are fatal; occurrences quoted inside backticks and inside headings are
  # allowed because the TODO sweep section has to name the sweep it ran.
  if sed -E 's/`[^`]*`//g' "$REPORT_ABS" | grep -vE '^#' | grep -qE 'TODO|FIXME|XXX|HACK'; then
    fail "bare TODO/FIXME/XXX/HACK marker outside backticks in report"
  fi
fi

# ---------------------------------------------------------------- DOD-01
if [ "$RUN_PACKAGES" -eq 1 ]; then
  n=$(grep -cE '^### PKG-' "$REPORT_ABS")
  [ "$n" -eq "${#PACKAGES[@]}" ] || fail "expected ${#PACKAGES[@]} '### PKG-' blocks, found $n"
  for pkg in "${PACKAGES[@]}"; do
    body=$(block_of "^### PKG-${pkg}\$")
    [ -n "$body" ] || fail "package block missing: PKG-${pkg}"
    cited=$(printf '%s\n' "$body" | grep -E '^- path: ' | head -1 | sed -E 's/^- path: //' | awk '{print $1}')
    [ -n "$cited" ] || fail "PKG-${pkg} has no '- path:' citation"
    [ -d "$TOP/$cited" ] || fail "PKG-${pkg} cites a missing directory: $cited"
    case "$cited" in
      "src/main/java/com/example/utiltools/$pkg"|"src/main/java/com/example/utiltools/$pkg/") ;;
      *) fail "PKG-${pkg} path does not match the package name: $cited" ;;
    esac
    # DOD-01 names the three fields each block must declare, so a block that cites a path but
    # states no role/endpoint surface/dependency is not an inventory entry.
    for field in role endpoints shared-dep; do
      printf '%s\n' "$body" | grep -qE "^- ${field}: .+" \
        || fail "PKG-${pkg} has no '- ${field}:' row"
    done
  done
  # Plan §4.1 makes §1 open with the `- inventory:` block *and then one paragraph naming the request
  # pipeline in order*, and DOD-01 binds that clause to this flag — so a report that inventories every
  # package but never states the ordered path is not a DOD-01 pass (Test_Result_v5 B-01/B-01a: the
  # clause was absent while this branch still exited 0).
  # The paragraph is the first non-blank, non-`- ` line after the block's `- output:` row; the stages
  # of plan §4.1's chain are matched by first byte offset, so "in order" means increasing position
  # rather than mere presence, and the scope literal DOD-01 names is required alongside the filter
  # itself. `controller` is matched case-insensitively because the paragraph names it as the generic
  # owning-controller stage rather than as a class.
  pipeline=$(awk '/^## 1\. /{p=1; next} p && /^## /{exit} p' "$REPORT_ABS" \
    | awk '/^- output: /{seen=1; next} seen && /^[[:space:]]*$/ {next} seen && /^- /{next} seen {print; exit}')
  [ -n "${pipeline:-}" ] \
    || fail "§1 has no paragraph after the '- inventory:' block naming the request pipeline in order"
  prev_off=-1
  for stage in RequestIdFilter RateLimitFilter controller AuditedOperations AuditJournalService CircuitBreaker; do
    off=$(printf '%s\n' "$pipeline" | grep -boi -- "$stage" | head -1 | cut -d: -f1)
    [ -n "${off:-}" ] || fail "§1 pipeline paragraph does not name $stage"
    [ "$off" -gt "$prev_off" ] \
      || fail "§1 pipeline paragraph names $stage out of order (byte $off not after $prev_off)"
    prev_off=$off
  done
  printf '%s\n' "$pipeline" | grep -qF -- '/api/v1/utils/' \
    || fail "§1 pipeline paragraph does not state the RateLimitFilter scope '/api/v1/utils/*'"
fi

# ------------------------------------------------- DOD-03 + DOD-04 (INV-01)
if [ "$RUN_INVENTORY" -eq 1 ]; then
  # Exactly one measured inventory line: the fields are recomputed below, so a second line could
  # publish a different set of numbers under the same key names.
  n_inv=$(grep -cE '^- inventory: ' "$REPORT_ABS")
  [ "$n_inv" -eq 1 ] || fail_pinned "$PINNED_INVENTORY_KEY line" "expected exactly one '- inventory:' line, found $n_inv"
  inv=$(grep -E '^- inventory: ' "$REPORT_ABS" | head -1)
  declare -A published=()
  for key in main-sources pkg-dirs test-dirs mirrored test-sources; do
    val=$(printf '%s\n' "$inv" | grep -oE "${key}=[0-9]+" | head -1 | cut -d= -f2)
    [ -n "$val" ] || fail_pinned "$PINNED_INVENTORY_KEY $key" "'- inventory:' line publishes no '${key}=' field"
    published["$key"]=$val
  done

  # The entry point is the one source the reader hits first and the one file outside a package
  # directory; it must be named and it must resolve.
  n_ep=$(grep -cE '^- entry-point: ' "$REPORT_ABS")
  [ "$n_ep" -eq 1 ] || fail_pinned "$PINNED_INVENTORY_KEY entry-point" \
    "expected exactly one '- entry-point:' line, found $n_ep"
  ep=$(grep -E '^- entry-point: ' "$REPORT_ABS" | head -1 | sed -E 's/^- entry-point: //' | awk '{print $1}')
  [ -n "$ep" ] || fail_pinned "$PINNED_INVENTORY_KEY entry-point" "'- entry-point:' names no path"
  [ -f "$TOP/$ep" ] || fail_pinned "$PINNED_INVENTORY_KEY entry-point" "'- entry-point:' does not resolve to a file: $ep"

  # The inventory's own evidence triple: an `- evidence:` command whose re-run prints the
  # `- output:` tail verbatim, with a matching `- exit:`.
  sec1=$(awk '/^## 2\. /{if (p) exit} /^## 1\. /{p=1} p' "$REPORT_ABS")
  printf '%s\n' "$sec1" | grep -qE '^- evidence: .+' || fail "§1 has no '- evidence:' command"
  printf '%s\n' "$sec1" | grep -qE '^- exit: [0-9]+'  || fail "§1 has no integer '- exit:'"
  raw_out=$(printf '%s\n' "$sec1" | grep -E '^- output: ' | head -1 | sed -E 's/^- output: //')
  [ -n "$raw_out" ] || fail "§1 has no '- output:' tail"
  out_text=$(untick "$raw_out")

  # Recompute every field from the tree and compare.
  m_main=$(find "$SRC_MAIN" -name '*.java' | wc -l | tr -d ' ')
  m_pkg=$(find "$SRC_MAIN" -mindepth 1 -maxdepth 1 -type d | wc -l | tr -d ' ')
  m_tdir=$(find "$SRC_TEST" -mindepth 1 -maxdepth 1 -type d | wc -l | tr -d ' ')
  m_mir=0
  for d in "$SRC_MAIN"/*/; do
    [ -d "$SRC_TEST/$(basename "$d")" ] && m_mir=$((m_mir + 1))
  done
  m_tsrc=$(find "$SRC_TEST" -name '*.java' | wc -l | tr -d ' ')
  for pair in "main-sources:$m_main" "pkg-dirs:$m_pkg" "test-dirs:$m_tdir" \
              "mirrored:$m_mir" "test-sources:$m_tsrc"; do
    key=${pair%%:*}; measured=${pair#*:}
    [ "${published[$key]}" -eq "$measured" ] \
      || fail_pinned "$PINNED_INVENTORY_KEY ${key}" \
           "'- inventory:' says ${published[$key]}, the tree measures $measured"
  done

  # INV-01: every integer in §1/§2 is either an inventory field or an output tail, so a bare
  # "holds exactly N ..." / "holds N package directories" / "mirrors N of them" sentence is
  # fatal — those shapes are what drifted from the tree in the previous revision, and the count
  # nouns in BARE_COUNT_ERE are the ones §1/§2 use to describe the tree.
  sec12=$(awk '/^## 3\. /{if (p) exit} /^## 1\. /{p=1} p' "$REPORT_ABS")
  bare=$(printf '%s\n' "$sec12" | grep -vE '^- (inventory|output): ' \
    | grep -nE "$BARE_COUNT_ERE" | head -1)
  [ -z "$bare" ] \
    || fail_pinned "$PINNED_BARE_COUNT" "§1/§2 prose count is not an '- inventory:' field: $bare"

  # §4.10 rows 9 and the tail half of the evidence triple run *after* the row 1-5 recompute
  # (Test_Result_v5 N-01): an incoherent inventory edit used to surface the unpinned
  # "'- output:' is not printed by its own '- evidence:' command" line before the pinned
  # `FAIL: inventory <key>` literal, which is the first-failure identity §4.10 row 1 promises.
  out_len=${#out_text}
  [ "$out_len" -le "$MAX_OUTPUT" ] || fail_pinned "$PINNED_OUTPUT_LEN" \
    "§1 '- output:' is $out_len chars, over the $MAX_OUTPUT limit"
  inv_cmd=$(printf '%s\n' "$sec1" | grep -E '^- evidence: ' | head -1 | sed -E 's/^- evidence: //')
  inv_cmd=$(untick "$inv_cmd")
  inv_real=$(eval "$inv_cmd" 2>&1)
  printf '%s' "$inv_real" | grep -qF -- "$out_text" \
    || fail "§1 '- output:' is not printed by its own '- evidence:' command: $out_text"
fi

# ---------------------------------------------------------------- DOD-06
if [ "$RUN_PATHS" -eq 1 ]; then
  # Deduplicated: N copies of one `- path:` line prove the line count, not the coverage, so the
  # threshold is applied to distinct values (see Open_Findings NIT on --paths). Distinctness is
  # decided after canonicalization, otherwise `./README.md`, `README.md` and `a/../README.md` pass
  # as three citations of the same file.
  mapfile -t cited_paths < <(grep -E '^- path: ' "$REPORT_ABS" | sed -E 's/^- path: //' \
    | awk '{print $1}' \
    | while IFS= read -r p; do absolute_path "$p"; done \
    | while IFS= read -r abs; do
        case "$abs" in
          "$TOP"/*) printf '%s\n' "${abs#"$TOP"/}" ;;
          *) printf '%s\n' "$abs" ;;
        esac
      done \
    | sort -u)
  # A citation still absolute after canonicalization resolved outside $TOP, so probing it
  # verbatim would let a foreign file (e.g. /etc/passwd) pad the threshold with a path the repo
  # does not own; it is fatal, not merely unresolved (v3 NIT on --paths).
  for p in "${cited_paths[@]}"; do
    case "$p" in
      "$TOP"|"$TOP"/*) ;;
      /*) fail_pinned "$PINNED_ABS_PATH" "$p" ;;
    esac
  done
  [ "${#cited_paths[@]}" -ge "$MIN_PATHS" ] || fail "only ${#cited_paths[@]} distinct '- path:' citations, need >= $MIN_PATHS"
  missing=0
  for p in "${cited_paths[@]}"; do
    case "$p" in
      /*) probe="$p" ;;
      *) probe="$TOP/$p" ;;
    esac
    if [ ! -e "$probe" ]; then printf 'MISSING: %s\n' "$p"; missing=$((missing + 1)); fi
  done
  [ "$missing" -eq 0 ] || fail "$missing cited path(s) unresolved"
fi

# ---------------------------------------------------------------- DOD-07
if [ "$RUN_EVIDENCE" -eq 1 ]; then
  mapfile -t gaps < <(gap_headings)
  [ "${#gaps[@]}" -ge "$MIN_GAPS" ] || fail "only ${#gaps[@]} GAP blocks, need >= $MIN_GAPS"
  for g in "${gaps[@]}"; do
    body=$(block_of "^### ${g}([^0-9]|$)")
    printf '%s\n' "$body" | grep -qE '^- evidence: .+' || fail "${g} has no '- evidence:'"
    printf '%s\n' "$body" | grep -qE '^- exit: [0-9]+' || fail "${g} has no integer '- exit:'"
    raw_out=$(printf '%s\n' "$body" | grep -E '^- output: ' | head -1 | sed -E 's/^- output: //')
    [ -n "$raw_out" ] || fail "${g} has no '- output:' tail"
    out_text=$(untick "$raw_out")
    out_len=${#out_text}
    [ "$out_len" -le "$MAX_OUTPUT" ] || fail_pinned "$PINNED_OUTPUT_LEN" \
      "${g} '- output:' is $out_len chars, over the $MAX_OUTPUT limit"
    # The tail must be reproduced by the block's own command: a hand-written tail that the
    # evidence does not print is a claim, not evidence.
    ev_cmd=$(printf '%s\n' "$body" | grep -E '^- evidence: ' | head -1 | sed -E 's/^- evidence: //')
    ev_cmd=$(untick "$ev_cmd")
    ev_real=$(eval "$ev_cmd" 2>&1)
    printf '%s' "$ev_real" | grep -qF -- "$out_text" \
      || fail "${g} '- output:' is not printed by its own '- evidence:' command: $out_text"
  done
fi

# ---------------------------------------------------------------- DOD-08
if [ "$RUN_GAPS" -eq 1 ]; then
  mapfile -t gaps < <(gap_headings)
  [ "${#gaps[@]}" -ge "$MIN_GAPS" ] || fail "only ${#gaps[@]} GAP blocks, need >= $MIN_GAPS"
  for g in "${gaps[@]}"; do
    body=$(block_of "^### ${g}([^0-9]|$)")
    gate=$(printf '%s\n' "$body" | grep -E '^- gate: ' | head -1)
    for f in F1= F2= F3=; do
      printf '%s' "$gate" | grep -q "$f" || fail "${g} does not declare filter $f in '- gate:'"
    done
    tier=$(printf '%s\n' "$body" | grep -E '^- tier: ' | head -1 | sed -E 's/^- tier: //' | awk '{print $1}')
    case "$tier" in
      S|M|L) ;;
      *) fail "${g} has no tier in {S,M,L} (found '${tier}')" ;;
    esac
    score_line=$(printf '%s\n' "$body" | grep -E '^- score: ' | head -1)
    score=$(printf '%s\n' "$score_line" | sed -E 's/^- score: //' | grep -oE '^[0-9]+')
    [ -n "$score" ] || fail "${g} has no integer '- score:'"
    [ "$score" -ge 0 ] && [ "$score" -le 14 ] || fail "${g} score $score outside 0..14"
    # When the rubric's sub-scores are published parenthetically they must sum to the score, so a
    # later edit to one sub-score cannot silently leave the total stale.
    if printf '%s' "$score_line" | grep -qE '^- score: [0-9]+ \('; then
      sub=$(printf '%s' "$score_line" | sed -E 's/^[^(]*//; s/\).*//')
      s_ev=$(printf '%s' "$sub" | grep -oE '(evidence|ev)[^0-9]*[0-9]+' | grep -oE '[0-9]+$' | head -1)
      s_impact=$(printf '%s' "$sub" | grep -oE 'impact[^0-9]*[0-9]+' | grep -oE '[0-9]+$' | head -1)
      s_blast=$(printf '%s' "$sub" | grep -oE 'blast[^0-9]*[0-9]+' | grep -oE '[0-9]+$' | head -1)
      s_verif=$(printf '%s' "$sub" | grep -oE 'verif[a-z]*[^0-9]*[0-9]+' | grep -oE '[0-9]+$' | head -1)
      for v in s_ev s_impact s_blast s_verif; do
        [ -n "${!v}" ] || fail "${g} '- score:' parenthesis has no $v sub-score"
      done
      sub_sum=$((2 * s_ev + 2 * s_impact + s_blast + s_verif))
      [ "$sub_sum" -eq "$score" ] || fail_pinned "$PINNED_SCORE_SUM" \
        "${g} sub-scores sum to $sub_sum but '- score:' says $score"
    fi
  done
fi

# ---------------------------------------------------------------- DOD-09
if [ "$RUN_SELECTION" -eq 1 ]; then
  n=$(grep -cE '^### SELECTED:' "$REPORT_ABS")
  [ "$n" -eq 1 ] || fail "expected exactly one '### SELECTED:' block, found $n"
  sel=$(block_of '^### SELECTED:')
  chosen=$(printf '%s\n' "$sel" | grep -E '^- selected-gap: ' | head -1 | sed -E 's/^- selected-gap: //' | awk '{print $1}')
  [ -n "$chosen" ] || fail "SELECTED block has no '- selected-gap:'"
  grep -qE "^### ${chosen}([^0-9]|$)" "$REPORT_ABS" || fail "SELECTED points at unknown gap: $chosen"
  printf '%s\n' "$sel" | grep -qE '^- scope: .+' || fail "SELECTED block has no '- scope:'"
  printf '%s\n' "$sel" | grep -qE '^- rationale: .+' || fail "SELECTED block has no '- rationale:'"
  reqs=$(printf '%s\n' "$sel" | grep -cE '^- proposed-req: .+')
  [ "$reqs" -ge 1 ] || fail "SELECTED block has no '- proposed-req:'"
  dods=$(printf '%s\n' "$sel" | grep -cE '^- proposed-dod: .+')
  [ "$dods" -ge "$MIN_DOD" ] || fail "SELECTED block has $dods '- proposed-dod:' rows, need >= $MIN_DOD"
  idents=$(printf '%s\n' "$sel" | grep -cE '^- selected-identifier: .+')
  [ "$idents" -ge 1 ] || fail "SELECTED block has no '- selected-identifier:'"
  while IFS= read -r row; do
    [ -n "$row" ] || continue
    case "$row" in
      *qa-probe:*|*audit-only:*) continue ;;
      *test:*)
        tok=$(printf '%s' "$row" | sed -E 's/.*test: *//' | tr -d '`"' | awk '{print $1}')
        tok=${tok%.java}
        [ -n "$tok" ] || fail "proposed-dod 'test:' row names no test: $row"
        [ -n "$(find "$TOP/src/test" -name "${tok}.java" -print -quit)" ] \
          || fail "proposed-dod 'test:' row names a test that does not exist today: $tok"
        ;;
      *) fail "proposed-dod row uses an unsupported verification mode: $row" ;;
    esac
  done < <(printf '%s\n' "$sel" | grep -E '^- proposed-dod: ')
fi

# ---------------------------------------------------------------- DOD-10
if [ "$RUN_NOT_IMPL" -eq 1 ]; then
  sel=$(block_of '^### SELECTED:')
  mapfile -t idents < <(printf '%s\n' "$sel" | grep -E '^- selected-identifier: ' \
    | sed -E 's/^- selected-identifier: //')
  [ "${#idents[@]}" -ge 1 ] || fail "no '- selected-identifier:' patterns to verify"
  hits_total=0
  for re in "${idents[@]}"; do
    [ -n "$re" ] || continue
    if hits=$(grep -rEn "$re" "$TOP/src/main" "$TOP/README.md" 2>/dev/null); then
      hits_total=$((hits_total + $(printf '%s\n' "$hits" | grep -c .)))
    fi
  done
  [ "$hits_total" -eq 0 ] || fail "selected task looks already implemented: $hits_total match(es) for the selected identifiers in src/main or README.md"
fi

# ---------------------------------------------------------------- DOD-11
if [ "$RUN_NO_SRC" -eq 1 ]; then
  PROTECTED=(src/main src/test README.md pom.xml .ait)
  # The working-tree half is the assertion plan §4.9 pins for this flag.
  dirty=$(git -C "$TOP" status --porcelain -- "${PROTECTED[@]}")
  [ -z "$dirty" ] || fail "task touched non-report paths: $(printf '%s' "$dirty" | tr '\n' ';')"
  # DOD-11 also says "the commit adds only the report plus tools/check_discovery_report.sh", so
  # the committed range is asserted too: a `src/main` edit that is already committed leaves the
  # working tree clean and would otherwise pass (the blind spot named in the v4 review NIT).
  base=""
  for ref in origin/main main; do
    if git -C "$TOP" rev-parse --verify --quiet "$ref" >/dev/null 2>&1; then base="$ref"; break; fi
  done
  # Fail closed (Review_Result_v5 SHOULD_FIX / Test_Result_v5 N-04): the previous guard wrapped the
  # committed-range half in `if [ -n "$base" ]`, so with neither origin/main nor main present the
  # assertion silently skipped and an already-committed `src/main` edit passed. A probe that cannot
  # establish its baseline must not report success, so an unresolvable base or merge-base is a FAIL
  # with an explicit diagnostic. The root commit is deliberately *not* used as a fallback: it would
  # also flag every historical commit of the repo as if it belonged to this task.
  [ -n "$base" ] \
    || fail "no base ref resolvable (neither origin/main nor main); cannot verify the committed range"
  fork=$(git -C "$TOP" merge-base "$base" HEAD 2>/dev/null || true)
  [ -n "$fork" ] \
    || fail "no merge-base between $base and HEAD; cannot verify the committed range"
  committed=$(git -C "$TOP" diff --name-only "$fork"...HEAD -- "${PROTECTED[@]}")
  [ -z "$committed" ] \
    || fail "committed change to a protected path since $base: $(printf '%s' "$committed" | tr '\n' ';')"
fi

printf 'OK: %s\n' "$REPORT"
exit 0
