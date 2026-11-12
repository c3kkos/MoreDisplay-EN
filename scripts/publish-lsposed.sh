#!/usr/bin/env bash
# Push module metadata and the signed APK to the official module repository Xposed-Modules-Repo/<package>.
#
# Usage:
#   ./scripts/publish-lsposed.sh <tag> <title> <notes-file> <apk> [<apk>...]
#   # the tag must be `<versionCode>-<versionName>`, e.g. 1-1.0
#
# Required environment:
#   GH_TOKEN        PAT with write access to Xposed-Modules-Repo/<package> (injected as a secret in CI)
#   LSPOSED_REPO    optional, defaults to Xposed-Modules-Repo/io.github.ynkcc.moredisplay
set -euo pipefail

cd "$(dirname "$0")/.."

TAG="${1:?usage: publish-lsposed.sh <tag> <title> <notes-file> <apk>...}"
TITLE="${2:?missing title}"
NOTES_FILE="${3:?missing notes file}"
shift 3
APKS=("$@")
[[ ${#APKS[@]} -gt 0 ]] || { echo "missing apk" >&2; exit 1; }

REPO="${LSPOSED_REPO:-Xposed-Modules-Repo/io.github.ynkcc.moredisplay}"
: "${GH_TOKEN:?GH_TOKEN is not set}"

# Write (or update) the metadata files in the repository. The manager uses SUMMARY as the list summary and README.md as the detail page.
put_file() {
    local path="$1" src="$2"
    local sha content
    sha="$(gh api "repos/$REPO/contents/$path" --jq .sha 2>/dev/null || true)"
    content="$(base64 -w0 <"$src")"
    if [[ -n "$sha" ]]; then
        gh api -X PUT "repos/$REPO/contents/$path" \
            -f message="Update $path for $TITLE" \
            -f content="$content" -f sha="$sha" >/dev/null
        echo "updated $path"
    else
        gh api -X PUT "repos/$REPO/contents/$path" \
            -f message="Add $path for $TITLE" \
            -f content="$content" >/dev/null
        echo "created $path"
    fi
}

put_file SUMMARY dist/SUMMARY
put_file README.md dist/README.md

# The module repository ships packages via releases with an attached apk; the tag must be <versionCode>-<versionName>.
if gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1; then
    gh release upload "$TAG" --repo "$REPO" --clobber "${APKS[@]}"
    echo "uploaded to existing release $TAG"
else
    gh release create "$TAG" --repo "$REPO" --title "$TITLE" \
        --notes-file "$NOTES_FILE" "${APKS[@]}"
    echo "created release $TAG"
fi

echo "published $TAG -> https://github.com/$REPO/releases/tag/$TAG"
