#!/usr/bin/env bash
# Sync fork with upstream and report changes
# Used by Hermes cron job — runs Tue/Thu 1:00 AM UTC
set -euo pipefail

REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
STATE_FILE="$REPO_DIR/.upstream-last-hash"

cd "$REPO_DIR"

# ---- Stash local changes if any ----
STASH_NEEDED=false
if [ -n "$(git status --porcelain)" ]; then
  STASH_NEEDED=true
  git stash push -m "auto-stash before upstream sync $(date +%Y-%m-%d_%H%M)" 2>/dev/null
fi

# ---- Fetch upstream ----
echo "=== FETCHING UPSTREAM ==="
git fetch upstream --tags 2>&1

LAST_HASH=""
if [ -f "$STATE_FILE" ]; then
  LAST_HASH=$(cat "$STATE_FILE")
fi

CURRENT_HASH=$(git rev-parse upstream/main)

if [ "$LAST_HASH" = "$CURRENT_HASH" ]; then
  echo "NO_CHANGES: Already at $CURRENT_HASH"
  if [ "$STASH_NEEDED" = true ]; then
    git stash pop 2>/dev/null || true
  fi
  exit 0
fi

# ---- Show new commits ----
if [ -n "$LAST_HASH" ]; then
  NEW_COUNT=$(git rev-list --count "${LAST_HASH}..upstream/main" 2>/dev/null || echo "0")
  echo "=== NEW COMMITS ($NEW_COUNT total) ==="
  git log --oneline "${LAST_HASH}..upstream/main" --format="%h %s" 2>/dev/null || true
else
  echo "=== CURRENT UPSTREAM HEAD ==="
  git log --oneline upstream/main -1
fi

# ---- Merge upstream into fork's main ----
echo ""
echo "=== MERGING UPSTREAM ==="
git checkout main
if git merge upstream/main --ff-only 2>/dev/null; then
  echo "Fast-forward merge successful."
  echo "=== PUSHING TO FORK ==="
  git push origin main 2>&1 || echo "WARNING: Push failed (check OAuth scope for workflow files)"
else
  echo "Fast-forward not possible, trying normal merge..."
  git merge upstream/main --no-edit 2>&1
  git push origin main 2>&1 || echo "WARNING: Push failed (check OAuth scope for workflow files)"
fi

# ---- Restore local changes ----
if [ "$STASH_NEEDED" = true ]; then
  echo ""
  echo "=== RESTORING LOCAL CHANGES === "
  git stash pop 2>/dev/null || echo "Stash pop skipped (may have conflicts — check git stash list)"
fi

# ---- Show new releases (v-prefixed tags) ----
echo ""
echo "=== NEW RELEASES ==="
LATEST=$(git tag -l 'v*' --sort=-version:refname | head -3 2>/dev/null || true)
if [ -n "$LATEST" ]; then
  while IFS= read -r tag; do
    TAG_DATE=$(git log -1 --format=%cs "$tag" 2>/dev/null || echo "?")
    echo "$tag  ($TAG_DATE)"
  done <<< "$LATEST"
fi

# ---- Write state file (do this even if push failed) ----
echo "$CURRENT_HASH" > "$STATE_FILE"
echo ""
echo "STATE_UPDATED: $CURRENT_HASH"
