#!/usr/bin/env bash
set -Eeuo pipefail

# Sync the source branch (main) into the upgrade branch (2.0.0) safely:
#   1. Snapshot both branches into dated, PR-scoped temp branches.
#   2. Merge the upgrade temp into the source temp.
#   3. If the merge is clean, run the build as a gate.
#   4. If the build passes, fast-forward / force-with-lease the upgrade branch
#      to the verified merge commit.
# On a merge conflict or a build failure the upgrade branch is left untouched
# and a GitHub issue + report are produced for a human to resolve.

SOURCE_BRANCH="${SOURCE_BRANCH:-main}"
UPGRADE_BRANCH="${UPGRADE_BRANCH:-2.0.0}"
REMOTE="${REMOTE:-origin}"
PUSH_CHANGES="${PUSH_CHANGES:-true}"
DATE_FORMAT="${DATE_FORMAT:-mmddyyyy}"
BUILD_COMMAND="${BUILD_COMMAND:-mvn -U -B test verify}"
SKIP_BUILD="${SKIP_BUILD:-false}"
CONFLICT_FILE="${CONFLICT_FILE:-conflicted-files.txt}"
REPORT_FILE="${REPORT_FILE:-branch-sync-conflict.md}"
BUILD_LOG="${BUILD_LOG:-build-failure.log}"
PRESERVE_ARTIFACTS="${PRESERVE_ARTIFACTS:-false}"
CREATE_ISSUE="${CREATE_ISSUE:-false}"
ALLOW_DRY_RUN_ISSUE="${ALLOW_DRY_RUN_ISSUE:-false}"
ISSUE_LABELS="${ISSUE_LABELS:-branch-sync,spring-upgrade}"
ISSUE_ASSIGNEE="${ISSUE_ASSIGNEE:-${DEVELOPER_GITHUB_LOGIN:-}}"

usage() {
  cat <<'USAGE'
Usage:
  sync-primary-to-upgrade.sh [options]

Merges the source branch into the upgrade branch on throwaway temp branches,
gates the result on a build, and only then advances the upgrade branch.

Options:
  --source BRANCH         Source branch to merge from. Default: main
  --upgrade-branch BRANCH Upgrade branch to advance. Default: 2.0.0
  --remote REMOTE         Git remote name. Default: origin
  --date-format FORMAT    Temp-branch date format: mmddyyyy or yyyymmdd. Default: mmddyyyy
  --build-command CMD     Build gate command. Default: "mvn -U -B test verify"
  --skip-build            Skip the build gate (merge clean -> update upgrade branch)
  --conflict-file FILE    File to write conflicted paths to. Default: conflicted-files.txt
  --report-file FILE      Markdown failure report file. Default: branch-sync-conflict.md
  --build-log FILE        Build output log file. Default: build-failure.log
  --preserve-artifacts    Keep conflict/report/build-log files during dry-run
  --create-issue          Create a GitHub issue on failure. Skipped in dry-run by default
  --issue-labels LABELS   Comma-separated base labels for created issue
  --issue-assignee USER   GitHub username to assign when creating a failure issue
  --dry-run               Merge and build, but do not update the upgrade branch
  --push                  Push changes. Overrides PUSH_CHANGES=false
  -h, --help              Show this help

Environment variables with matching names are also supported:
  SOURCE_BRANCH, UPGRADE_BRANCH, REMOTE, DATE_FORMAT, BUILD_COMMAND, SKIP_BUILD,
  CONFLICT_FILE, REPORT_FILE, BUILD_LOG, PUSH_CHANGES, PRESERVE_ARTIFACTS,
  CREATE_ISSUE, ISSUE_LABELS, ISSUE_ASSIGNEE, PR_NUMBER, DEVELOPER_GITHUB_LOGIN

Temp branch names use:
  <source>-<date>-<pr-tag>-tmp   and   <upgrade>-<date>-<pr-tag>-tmp
where <pr-tag> is pr<number> when known, otherwise the source short SHA.

Exit codes:
  0  success (upgrade branch updated, already up to date, or clean dry-run)
  2  merge conflict (upgrade branch untouched)
  3  build failure (upgrade branch untouched)

Example:
  PUSH_CHANGES=false .github/scripts/sync-primary-to-upgrade.sh \
    --source main --upgrade-branch 2.0.0 --dry-run
USAGE
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --source)
      SOURCE_BRANCH="${2:?Missing value for --source}"
      shift 2
      ;;
    --upgrade-branch)
      UPGRADE_BRANCH="${2:?Missing value for --upgrade-branch}"
      shift 2
      ;;
    --remote)
      REMOTE="${2:?Missing value for --remote}"
      shift 2
      ;;
    --date-format)
      DATE_FORMAT="${2:?Missing value for --date-format}"
      shift 2
      ;;
    --build-command)
      BUILD_COMMAND="${2:?Missing value for --build-command}"
      shift 2
      ;;
    --skip-build)
      SKIP_BUILD="true"
      shift
      ;;
    --conflict-file)
      CONFLICT_FILE="${2:?Missing value for --conflict-file}"
      shift 2
      ;;
    --report-file)
      REPORT_FILE="${2:?Missing value for --report-file}"
      shift 2
      ;;
    --build-log)
      BUILD_LOG="${2:?Missing value for --build-log}"
      shift 2
      ;;
    --preserve-artifacts)
      PRESERVE_ARTIFACTS="true"
      shift
      ;;
    --create-issue)
      CREATE_ISSUE="true"
      shift
      ;;
    --issue-labels)
      ISSUE_LABELS="${2:?Missing value for --issue-labels}"
      shift 2
      ;;
    --issue-assignee)
      ISSUE_ASSIGNEE="${2:?Missing value for --issue-assignee}"
      shift 2
      ;;
    --dry-run)
      PUSH_CHANGES="false"
      shift
      ;;
    --push)
      PUSH_CHANGES="true"
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      printf 'ERROR: Unknown argument: %s\n\n' "$1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

log() {
  printf '%s\n' "$*"
}

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

trim_whitespace() {
  local value="$1"

  value="${value#"${value%%[![:space:]]*}"}"
  value="${value%"${value##*[![:space:]]}"}"
  printf '%s\n' "$value"
}

require_clean_tree() {
  if ! git diff --quiet || ! git diff --cached --quiet; then
    git status --short
    fail "Working tree is not clean"
  fi
}

write_summary() {
  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    {
      echo "## Branch sync"
      echo ""
      echo "- Source: \`${SOURCE_BRANCH}\`"
      echo "- Upgrade branch: \`${UPGRADE_BRANCH}\`"
      echo "- Remote: \`${REMOTE}\`"
      echo "- Build command: \`${BUILD_COMMAND}\`"
      echo "- Skip build: \`${SKIP_BUILD}\`"
      echo "- Push changes: \`${PUSH_CHANGES}\`"
      echo "- Create issue: \`${CREATE_ISSUE}\`"
      echo ""
      echo "$1"
    } >> "$GITHUB_STEP_SUMMARY"
  fi
}

infer_pr_number() {
  local subject

  if [ -n "${PR_NUMBER:-}" ]; then
    printf '%s\n' "$PR_NUMBER"
    return
  fi

  subject="$(git log -1 --format='%s' "${REMOTE}/${SOURCE_BRANCH}")"
  if [[ "$subject" =~ \#([0-9]+) ]]; then
    printf '%s\n' "${BASH_REMATCH[1]}"
    return
  fi

  printf 'unknown\n'
}

# pr<number> when a PR is known, otherwise the source branch short SHA so the
# temp branch name is still unique on manual (workflow_dispatch) runs.
pr_tag() {
  local pr
  pr="$(infer_pr_number)"
  if [ "$pr" != "unknown" ]; then
    printf 'pr%s\n' "$pr"
  else
    git rev-parse --short "${REMOTE}/${SOURCE_BRANCH}"
  fi
}

developer_id() {
  if [ -n "${DEVELOPER_ID:-}" ]; then
    printf '%s\n' "$DEVELOPER_ID"
  elif [ -n "${GITHUB_ACTOR:-}" ]; then
    printf '%s\n' "$GITHUB_ACTOR"
  else
    git log -1 --format='%an <%ae>' "${REMOTE}/${SOURCE_BRANCH}"
  fi
}

workflow_run_url() {
  if [ -n "${GITHUB_SERVER_URL:-}" ] && [ -n "${GITHUB_REPOSITORY:-}" ] && [ -n "${GITHUB_RUN_ID:-}" ]; then
    printf '%s/%s/actions/runs/%s\n' "$GITHUB_SERVER_URL" "$GITHUB_REPOSITORY" "$GITHUB_RUN_ID"
  else
    printf 'not available outside GitHub Actions\n'
  fi
}

today_for_branch() {
  case "$DATE_FORMAT" in
    mmddyyyy)
      date '+%m%d%Y'
      ;;
    yyyymmdd)
      date '+%Y%m%d'
      ;;
    *)
      fail "Unsupported DATE_FORMAT: ${DATE_FORMAT}. Use mmddyyyy or yyyymmdd"
      ;;
  esac
}

restore_original_branch() {
  if [ -n "${ORIGINAL_BRANCH:-}" ]; then
    git switch -q "$ORIGINAL_BRANCH" >/dev/null 2>&1 || true
  elif [ -n "${ORIGINAL_SHA:-}" ]; then
    git switch -q --detach "$ORIGINAL_SHA" >/dev/null 2>&1 || true
  fi
}

delete_local_branch_if_present() {
  local branch="$1"

  if git show-ref --verify --quiet "refs/heads/${branch}"; then
    git branch -D "$branch" >/dev/null 2>&1 || true
  fi
}

cleanup_temp_branches() {
  restore_original_branch
  delete_local_branch_if_present "$source_temp"
  delete_local_branch_if_present "$upgrade_temp"
}

# Reason label used to title and label the failure issue.
failure_label() {
  case "$1" in
    merge-conflict) printf 'conflict\n' ;;
    build-failure)  printf 'build failure\n' ;;
    *)              printf '%s\n' "$1" ;;
  esac
}

issue_labels_for_reason() {
  local reason="$1" base
  base="$ISSUE_LABELS"
  printf '%s,%s\n' "$base" "$reason"
}

failure_issue_title() {
  local reason="$1" pr_number short_sha source_label
  pr_number="$(infer_pr_number)"
  short_sha="$(git rev-parse --short "${REMOTE}/${SOURCE_BRANCH}")"

  if [ "$pr_number" != "unknown" ]; then
    source_label="PR #${pr_number}"
  else
    source_label="${SOURCE_BRANCH}"
  fi

  printf 'Branch sync %s: %s -> %s (%s)\n' \
    "$(failure_label "$reason")" "$source_label" "$UPGRADE_BRANCH" "$short_sha"
}

create_failure_issue() {
  local reason="$1" issue_title labels label label_args assignee_args issue_status

  if [ "$CREATE_ISSUE" != "true" ]; then
    log "CREATE_ISSUE=false; GitHub issue creation skipped."
    return
  fi

  if [ "$PUSH_CHANGES" != "true" ] && [ "$ALLOW_DRY_RUN_ISSUE" != "true" ]; then
    log "Dry run detected; GitHub issue creation skipped."
    return
  fi

  command -v gh >/dev/null 2>&1 || fail "gh is required for --create-issue"

  issue_title="$(failure_issue_title "$reason")"
  label_args=()
  assignee_args=()

  if [ -n "$ISSUE_ASSIGNEE" ]; then
    assignee_args+=(--assignee "$ISSUE_ASSIGNEE")
  fi

  IFS=',' read -ra labels <<< "$(issue_labels_for_reason "$reason")"
  for label in "${labels[@]}"; do
    label="$(trim_whitespace "$label")"
    [ -n "$label" ] || continue
    label_args+=(--label "$label")
  done

  log "Creating GitHub issue: ${issue_title}"
  set +e
  gh issue create \
    --title "$issue_title" \
    --body-file "$REPORT_FILE" \
    "${assignee_args[@]}" \
    "${label_args[@]}"
  issue_status=$?
  set -e

  if [ "$issue_status" -eq 0 ]; then
    return
  fi

  if [ "${#label_args[@]}" -eq 0 ]; then
    if [ "${#assignee_args[@]}" -eq 0 ]; then
      log "GitHub issue creation failed."
      return
    fi

    log "GitHub issue creation with assignee failed. Retrying without assignee."
    set +e
    gh issue create \
      --title "$issue_title" \
      --body-file "$REPORT_FILE"
    issue_status=$?
    set -e

    if [ "$issue_status" -ne 0 ]; then
      log "GitHub issue creation without assignee also failed."
    fi

    return
  fi

  log "GitHub issue creation with labels failed. Retrying without labels."
  set +e
  gh issue create \
    --title "$issue_title" \
    --body-file "$REPORT_FILE" \
    "${assignee_args[@]}"
  issue_status=$?
  set -e

  if [ "$issue_status" -ne 0 ]; then
    if [ "${#assignee_args[@]}" -eq 0 ]; then
      log "GitHub issue creation without labels also failed."
      return
    fi

    log "GitHub issue creation without labels failed. Retrying without labels or assignee."
    set +e
    gh issue create \
      --title "$issue_title" \
      --body-file "$REPORT_FILE"
    issue_status=$?
    set -e

    if [ "$issue_status" -ne 0 ]; then
      log "GitHub issue creation without labels or assignee also failed."
    fi
  fi
}

write_failure_report() {
  local reason="$1" pr_number source_sha source_subject upgrade_sha author run_url

  pr_number="$(infer_pr_number)"
  source_sha="$(git rev-parse "${REMOTE}/${SOURCE_BRANCH}")"
  source_subject="$(git log -1 --format='%s' "${REMOTE}/${SOURCE_BRANCH}")"
  upgrade_sha="$ORIG_UPGRADE_SHA"
  author="$(developer_id)"
  run_url="$(workflow_run_url)"

  {
    if [ "$reason" = "build-failure" ]; then
      echo "# Branch Sync Build Failure"
      echo ""
      echo "Automatic branch sync merged \`${REMOTE}/${SOURCE_BRANCH}\` into \`${UPGRADE_BRANCH}\` cleanly, but the build gate failed. \`${UPGRADE_BRANCH}\` was left unchanged."
    else
      echo "# Branch Sync Conflict"
      echo ""
      echo "Automatic branch sync could not merge \`${REMOTE}/${UPGRADE_BRANCH}\` into \`${REMOTE}/${SOURCE_BRANCH}\`. \`${UPGRADE_BRANCH}\` was left unchanged."
    fi
    echo ""
    echo "## Context"
    echo ""
    echo "- Source branch: \`${SOURCE_BRANCH}\` (\`${source_sha}\`)"
    echo "- Source commit subject: \`${source_subject}\`"
    echo "- Upgrade branch: \`${UPGRADE_BRANCH}\` (\`${upgrade_sha}\`)"
    echo "- Pull request: \`${pr_number}\`"
    echo "- Developer: \`${author}\`"
    echo "- Issue assignee: \`${ISSUE_ASSIGNEE:-not assigned}\`"
    echo "- Source temp branch: \`${source_temp}\`"
    echo "- Upgrade temp branch: \`${upgrade_temp}\`"
    echo "- Build command: \`${BUILD_COMMAND}\`"
    echo "- Workflow run: ${run_url}"
    echo ""

    if [ "$reason" = "build-failure" ]; then
      echo "## Build Output (tail)"
      echo ""
      echo '```text'
      if [ -f "$BUILD_LOG" ]; then
        tail -n 200 "$BUILD_LOG"
      else
        echo "(build log not captured)"
      fi
      echo '```'
      echo ""
      echo "Full build output is in \`${BUILD_LOG}\` (uploaded as a workflow artifact on failure)."
    else
      echo "## Conflicted Files"
      echo ""
      sed 's/^/- `/' "$CONFLICT_FILE" | sed 's/$/`/'
      echo ""
      echo "Raw list of unresolved paths produced by:"
      echo ""
      echo '```bash'
      echo "git diff --name-only --diff-filter=U"
      echo '```'
    fi

    echo ""
    echo "## Manual Resolution"
    echo ""
    echo '```bash'
    echo "git fetch ${REMOTE}"
    echo "git switch --no-track -C ${source_temp} ${REMOTE}/${SOURCE_BRANCH}"
    echo "git merge ${REMOTE}/${UPGRADE_BRANCH}"
    if [ "$reason" != "build-failure" ]; then
      echo "# Resolve the conflicts listed above"
      echo "git add <resolved-files>"
      echo "git commit"
    fi
    echo "# Verify the merge builds"
    echo "${BUILD_COMMAND}"
    echo "# Once green, advance the upgrade branch (lease guards against races)"
    echo "git push --force-with-lease=${UPGRADE_BRANCH}:${upgrade_sha} ${REMOTE} HEAD:${UPGRADE_BRANCH}"
    echo '```'
    echo ""
    echo "## Copilot Prompt"
    echo ""
    echo "Use this prompt with GitHub Copilot after reproducing the merge locally:"
    echo ""
    echo '```text'
    if [ "$reason" = "build-failure" ]; then
      echo "Merging ${REMOTE}/${UPGRADE_BRANCH} into ${REMOTE}/${SOURCE_BRANCH} is clean but the build (${BUILD_COMMAND}) fails."
      echo "Fix the build while preserving the Spring Boot 4 / Spring AI 2.0 upgrade behavior from ${UPGRADE_BRANCH} and the changes from ${SOURCE_BRANCH}."
      echo "The failing build output is in ${BUILD_LOG}."
    else
      echo "Resolve the merge conflict from merging ${REMOTE}/${UPGRADE_BRANCH} into ${REMOTE}/${SOURCE_BRANCH}."
      echo "Preserve the Spring Boot 4 / Spring AI 2.0 upgrade behavior from ${UPGRADE_BRANCH}, and forward-port the relevant changes from ${SOURCE_BRANCH}."
      echo "Conflicted files are listed in ${CONFLICT_FILE}. After resolving, run ${BUILD_COMMAND}."
    fi
    echo '```'
  } > "$REPORT_FILE"
}

run_build() {
  local status
  if [ "$SKIP_BUILD" = "true" ]; then
    log "SKIP_BUILD=true; build gate skipped."
    return 0
  fi

  log "Running build gate: ${BUILD_COMMAND}"
  set +e
  bash -c "$BUILD_COMMAND" 2>&1 | tee "$BUILD_LOG"
  status="${PIPESTATUS[0]}"
  set -e
  return "$status"
}

git rev-parse --is-inside-work-tree >/dev/null 2>&1 || fail "Not inside a git repository"
require_clean_tree

ORIGINAL_BRANCH="$(git branch --show-current || true)"
ORIGINAL_SHA="$(git rev-parse HEAD)"

log "Fetching ${REMOTE}/${SOURCE_BRANCH} and ${REMOTE}/${UPGRADE_BRANCH}."

git fetch --prune "$REMOTE" \
  "+refs/heads/${SOURCE_BRANCH}:refs/remotes/${REMOTE}/${SOURCE_BRANCH}" \
  "+refs/heads/${UPGRADE_BRANCH}:refs/remotes/${REMOTE}/${UPGRADE_BRANCH}"

git show-ref --verify --quiet "refs/remotes/${REMOTE}/${SOURCE_BRANCH}" \
  || fail "Source branch ${REMOTE}/${SOURCE_BRANCH} not found"
git show-ref --verify --quiet "refs/remotes/${REMOTE}/${UPGRADE_BRANCH}" \
  || fail "Upgrade branch ${REMOTE}/${UPGRADE_BRANCH} not found"

# Pin the upgrade branch tip captured at fetch time. This is both the merge
# input and the expected value for the force-with-lease push, so a concurrent
# push to the upgrade branch cannot be clobbered.
ORIG_UPGRADE_SHA="$(git rev-parse "${REMOTE}/${UPGRADE_BRANCH}")"
ORIG_SOURCE_SHA="$(git rev-parse "${REMOTE}/${SOURCE_BRANCH}")"

today_part="$(today_for_branch)"
prtag="$(pr_tag)"
source_temp="${SOURCE_BRANCH}-${today_part}-${prtag}-tmp"
upgrade_temp="${UPGRADE_BRANCH}-${today_part}-${prtag}-tmp"

log "Source temp branch:  ${source_temp} (from ${REMOTE}/${SOURCE_BRANCH} @ $(git rev-parse --short "$ORIG_SOURCE_SHA"))"
log "Upgrade temp branch: ${upgrade_temp} (from ${REMOTE}/${UPGRADE_BRANCH} @ $(git rev-parse --short "$ORIG_UPGRADE_SHA"))"

# Create both snapshots, then merge the upgrade temp into the source temp.
delete_local_branch_if_present "$source_temp"
delete_local_branch_if_present "$upgrade_temp"
git branch --no-track "$upgrade_temp" "$ORIG_UPGRADE_SHA"
git switch --no-track -C "$source_temp" "$ORIG_SOURCE_SHA"
require_clean_tree

log "Merging ${upgrade_temp} into ${source_temp}"

set +e
git merge --no-edit "$upgrade_temp"
merge_status=$?
set -e

if [ "$merge_status" -ne 0 ]; then
  log "Merge conflict detected."

  git diff --name-only --diff-filter=U | sort > "$CONFLICT_FILE"
  write_failure_report "merge-conflict"

  log "Conflicted files:"
  cat "$CONFLICT_FILE"
  log "Report: ${REPORT_FILE}"

  write_summary "Merge conflict while merging \`${UPGRADE_BRANCH}\` into \`${SOURCE_BRANCH}\`. \`${UPGRADE_BRANCH}\` left unchanged. See \`${REPORT_FILE}\`."
  create_failure_issue "merge-conflict"

  git merge --abort || true
  cleanup_temp_branches

  if [ "$PUSH_CHANGES" != "true" ] && [ "$PRESERVE_ARTIFACTS" != "true" ]; then
    rm -f "$CONFLICT_FILE" "$REPORT_FILE"
  fi

  exit 2
fi

merged_sha="$(git rev-parse HEAD)"

if [ "$merged_sha" = "$ORIG_UPGRADE_SHA" ]; then
  log "${UPGRADE_BRANCH} already contains ${SOURCE_BRANCH}; nothing to update."
  write_summary "No changes needed. \`${UPGRADE_BRANCH}\` already contains \`${SOURCE_BRANCH}\`."
  cleanup_temp_branches
  exit 0
fi

log "Merge clean. Resulting commit: $(git rev-parse --short HEAD)"
git --no-pager log --oneline --decorate -n 5

if ! run_build; then
  log "Build failed."
  write_failure_report "build-failure"

  write_summary "Merge of \`${UPGRADE_BRANCH}\` into \`${SOURCE_BRANCH}\` was clean but the build failed. \`${UPGRADE_BRANCH}\` left unchanged. See \`${REPORT_FILE}\`."
  create_failure_issue "build-failure"

  cleanup_temp_branches

  if [ "$PUSH_CHANGES" != "true" ] && [ "$PRESERVE_ARTIFACTS" != "true" ]; then
    rm -f "$REPORT_FILE" "$BUILD_LOG"
  fi

  exit 3
fi

log "Build passed."

if [ "$PUSH_CHANGES" != "true" ]; then
  log "PUSH_CHANGES=false; dry run complete. ${UPGRADE_BRANCH} not updated."
  write_summary "Dry run succeeded. Merge clean and build green; \`${UPGRADE_BRANCH}\` was not updated."
  cleanup_temp_branches
  if [ "$PRESERVE_ARTIFACTS" != "true" ]; then
    rm -f "$BUILD_LOG"
  fi
  exit 0
fi

log "Updating ${REMOTE}/${UPGRADE_BRANCH} -> ${merged_sha} (force-with-lease against ${ORIG_UPGRADE_SHA})"
git push --force-with-lease="${UPGRADE_BRANCH}:${ORIG_UPGRADE_SHA}" \
  "$REMOTE" "HEAD:refs/heads/${UPGRADE_BRANCH}"

write_summary "Success. Merged \`${SOURCE_BRANCH}\` into \`${UPGRADE_BRANCH}\`, build passed, and \`${UPGRADE_BRANCH}\` was advanced to \`$(git rev-parse --short HEAD)\`."
log "Updated ${UPGRADE_BRANCH}."

cleanup_temp_branches
if [ "$PRESERVE_ARTIFACTS" != "true" ]; then
  rm -f "$BUILD_LOG"
fi
