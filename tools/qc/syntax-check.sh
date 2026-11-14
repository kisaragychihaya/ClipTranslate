#!/usr/bin/env bash
# ClipTranslate local QC gate: parse every Kotlin source under app/src/main/java with kotlinc.
#
# Deliberately no `set -e`: kotlinc exits non-zero for unresolved Android/Compose
# references, which is expected noise on a machine without the Android SDK.
# Only two things fail this gate:
#   1. syntax-level errors (Expecting / Unexpected / Unclosed / Syntax ...)
#   2. any error diagnostic whose source line mentions our own package prefix
#      `dev.cliptranslate.` (that means a reference between our modules is broken).
#      kotlinc 2.1.x prints only the simple name -- `error: unresolved reference
#      'AppNav'.` -- never the fully qualified one, so the package prefix is looked
#      up in the source line echoed right after the diagnostic
#      (`import dev.cliptranslate.ui.AppNav`).
set -u

DEFAULT_JAVA_HOME="/c/Users/admin/.local/opt/jdk21/jdk-21.0.13+9"
DEFAULT_KOTLINC_HOME="/c/Users/admin/.local/opt/kotlinc"

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
cd "$PROJECT_ROOT" || { echo "FAIL: cannot cd into $PROJECT_ROOT"; exit 2; }

# --- toolchain -------------------------------------------------------------
# JAVA_HOME / KOTLINC_HOME from the caller win; otherwise fall back to the
# portable installs on this machine.
if ! command -v java >/dev/null 2>&1; then
    for candidate in "${JAVA_HOME:-}" "$DEFAULT_JAVA_HOME"; do
        [ -n "$candidate" ] || continue
        if [ -x "$candidate/bin/java" ]; then
            export JAVA_HOME="$candidate"
            export PATH="$candidate/bin:$PATH"
            break
        fi
    done
fi

KOTLINC_HOME="${KOTLINC_HOME:-$DEFAULT_KOTLINC_HOME}"
export PATH="$KOTLINC_HOME/bin:$PATH"

if ! command -v kotlinc >/dev/null 2>&1; then
    echo "FAIL: kotlinc not found on PATH or in $KOTLINC_HOME/bin"
    echo "Summary: FAIL | syntax errors: n/a | own-package errors: n/a | total errors: n/a"
    exit 2
fi

# --- sources ---------------------------------------------------------------
SOURCES=()
while IFS= read -r file; do
    SOURCES+=("$file")
done < <(find app/src/main/java -type f -name '*.kt' 2>/dev/null | sort)

if [ "${#SOURCES[@]}" -eq 0 ]; then
    echo "FAIL: no .kt files found under app/src/main/java"
    echo "Summary: FAIL | syntax errors: n/a | own-package errors: n/a | total errors: n/a"
    exit 1
fi

echo "kt sources (${#SOURCES[@]}):"
printf '  %s\n' "${SOURCES[@]}"

# --- run kotlinc (parse/analyze only, no -d) -------------------------------
RAW_LOG="${TMPDIR:-/tmp}/cliptranslate-kotlinc.log"
kotlinc "${SOURCES[@]}" >"$RAW_LOG" 2>&1
KOTLINC_EXIT=$?

ALL_ERRORS="$(grep -E 'error: ' "$RAW_LOG" || true)"
SYNTAX_ERRORS="$(printf '%s\n' "$ALL_ERRORS" | grep -E 'error: (Expecting|Unexpected|Unclosed|unclosed|Syntax)' || true)"
OWN_ERRORS="$(awk -v pat='dev[.]cliptranslate[.]' '
    BEGIN { diag = "" }
    /: error: / {
        if (diag != "" && diag ~ pat) print diag
        diag = $0
        next
    }
    {
        if (diag != "") {
            if (diag ~ pat || $0 ~ pat) print diag
            diag = ""
        }
    }
    END { if (diag != "" && diag ~ pat) print diag }
' "$RAW_LOG")"

count_lines() {
    if [ -z "$1" ]; then
        printf '0'
    else
        printf '%s\n' "$1" | wc -l | tr -d ' '
    fi
}

SYNTAX_COUNT="$(count_lines "$SYNTAX_ERRORS")"
OWN_COUNT="$(count_lines "$OWN_ERRORS")"
TOTAL_COUNT="$(count_lines "$ALL_ERRORS")"

echo ""
echo "kotlinc exit code: $KOTLINC_EXIT (non-zero is expected: Android/Compose refs are unresolved locally)"
echo "raw kotlinc output: $RAW_LOG"

if [ "$SYNTAX_COUNT" -gt 0 ] || [ "$OWN_COUNT" -gt 0 ]; then
    echo ""
    echo "Failing lines:"
    printf '%s\n%s\n' "$SYNTAX_ERRORS" "$OWN_ERRORS" \
        | grep -E 'error: ' \
        | sort -u \
        | sed 's/^/  /'
    echo ""
    echo "Summary: FAIL | syntax errors: $SYNTAX_COUNT | own-package errors: $OWN_COUNT | total errors: $TOTAL_COUNT (incl. expected noise)"
    exit 1
fi

echo ""
echo "Summary: PASS | syntax errors: 0 | own-package errors: 0 | total errors: $TOTAL_COUNT (expected noise, ignored)"
exit 0
