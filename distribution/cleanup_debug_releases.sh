#!/usr/bin/env bash
# Delete expired debug-test releases from the private input repository.
#
# Safe by construction:
#   - only releases whose tag exactly matches debug-test-<run-id>-<attempt> are
#     considered;
#   - the release must be a prerelease;
#   - the release id of Apk-input-original is resolved first and every
#     candidate is compared against it (defence in depth if tags are renamed);
#   - if the protected release cannot be resolved, nothing is deleted;
#   - actual deletion additionally requires CLEANUP_DELETE_ENABLED=true, so the
#     workflow is dry-run only until an operator explicitly opts in.
#
# Required env:
#   GH_TOKEN   token with Contents: write on GH_REPO (the job's GITHUB_TOKEN)
#   GH_REPO    owner/name of the private input repository
# Optional env:
#   PROTECTED_TAG        default Apk-input-original
#   RETENTION_SECONDS    default 259200 (72h)
#   DRY_RUN              default false
#   DELETE_ENABLED       default false
set -euo pipefail

: "${GH_TOKEN:?GH_TOKEN is required}"
: "${GH_REPO:?GH_REPO is required}"

readonly PROTECTED_TAG="${PROTECTED_TAG:-Apk-input-original}"
readonly DEBUG_TAG_RE='^debug-test-[0-9]+-[0-9]+$'
readonly RETENTION_SECONDS="${RETENTION_SECONDS:-259200}"
DRY_RUN="${DRY_RUN:-false}"
DELETE_ENABLED="${DELETE_ENABLED:-false}"

if [ "$DELETE_ENABLED" != "true" ]; then
  DRY_RUN=true
  echo "deletion gate closed (CLEANUP_DELETE_ENABLED != true); forcing dry-run"
fi
[ "$DRY_RUN" = "true" ] && echo "mode: dry-run" || echo "mode: DELETE"

# Fail closed: resolve the protected release before considering any deletion.
protected_id="$(gh api "repos/$GH_REPO/releases/tags/$PROTECTED_TAG" --jq .id 2>/dev/null || true)"
if ! [[ "${protected_id:-}" =~ ^[0-9]+$ ]]; then
  echo "ERROR: cannot resolve protected release '$PROTECTED_TAG'; refusing to delete anything" >&2
  exit 1
fi
echo "protected release: $PROTECTED_TAG (id=$protected_id)"

now="$(date -u +%s)"
cutoff=$(( now - RETENTION_SECONDS ))
deleted=0
failed=0
skipped=0

while IFS=$'\t' read -r id tag prerelease created; do
  [ -n "${id:-}" ] || continue

  if ! [[ "$id" =~ ^[0-9]+$ ]]; then
    echo "skip (invalid id): $id"; skipped=$((skipped + 1)); continue
  fi
  if [ "$tag" = "$PROTECTED_TAG" ] || [ "$id" = "$protected_id" ]; then
    echo "skip (protected): $tag (id=$id)"; skipped=$((skipped + 1)); continue
  fi
  if [ "$prerelease" != "true" ]; then
    echo "skip (not a prerelease): $tag"; skipped=$((skipped + 1)); continue
  fi
  if ! [[ "$tag" =~ $DEBUG_TAG_RE ]]; then
    echo "skip (not a debug-test tag): $tag"; skipped=$((skipped + 1)); continue
  fi

  created_ts="$(date -u -d "$created" +%s 2>/dev/null || true)"
  if ! [[ "${created_ts:-}" =~ ^[0-9]+$ ]]; then
    echo "skip (unparseable created_at): $tag ($created)"; skipped=$((skipped + 1)); continue
  fi
  if [ "$created_ts" -ge "$cutoff" ]; then
    echo "keep (<72h): $tag"; continue
  fi

  if [ "$DRY_RUN" = "true" ]; then
    echo "would delete: $tag (id=$id, created=$created)"
    continue
  fi

  echo "deleting: $tag (id=$id, created=$created)"
  if ! gh api -X DELETE "repos/$GH_REPO/releases/$id"; then
    echo "ERROR: failed to delete release $id ($tag)" >&2
    failed=1
    continue
  fi
  deleted=$((deleted + 1))
  gh api -X DELETE "repos/$GH_REPO/git/refs/tags/$tag" >/dev/null 2>&1 \
    || echo "note: tag $tag already absent"
done < <(gh api --paginate "repos/$GH_REPO/releases?per_page=100" \
           --jq '.[] | [.id, .tag_name, .prerelease, .created_at] | @tsv')

echo "done: deleted=$deleted failed=$failed skipped=$skipped dry_run=$DRY_RUN"
exit "$failed"
