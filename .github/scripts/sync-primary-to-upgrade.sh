#!/usr/bin/env bash
set -Eeuo pipefail

SOURCE_BRANCH="${SOURCE_BRANCH:-main}"
BRANCH_PREFIX="${BRANCH_PREFIX:-2.0.0}"
REMOTE="${REMOTE:-origin}"
KEEP_BRANCHES="${KEEP_BRANCHES:-5}"
PUSH_CHANGES="${PUSH_CHANGES:-true}"
DATE_FORMAT="${DATE_FORMAT:-mmddyyyy}"
CONFLICT_FILE="${CONFLICT_FILE:-conflicted-files.txt}"
REPORT_FILE="${REPORT_FILE:-branch-sync-conflict.md}"
PRESERVE_ARTIFACTS="${PRESERVE_ARTIFACTS:-false}"
CREATE_ISSUE="${CREATE_ISSUE:-false}"
ALLOW_DRY_RUN_ISSUE="${ALLOW_DRY_RUN_ISSUE:-false}"
ISSUE_LABELS="${ISSUE_LABELS:-branch-sync,merge-conflict,spring-upgrade}"
ISSUE_ASSIGNEE="${ISSUE_ASSIGNEE:-${DEVELOPER_GITHUB_LOGIN:-}}"

usage() {
  cat <<'USAGE'
Usage:
  sync-primary-to-upgrade.sh [options]

Options:
  --source BRANCH        Source branch to merge from. Default: main
  --prefix PREFIX        Rolling upgrade branch prefix. Default: 2.0.0
  --remote REMOTE        Git remote name. Default: origin
  --keep COUNT           Number of generated upgrade branches to keep. Default: 5
  --date-format FORMAT   Branch date format: mmddyyyy or yyyymmdd. Default: mmddyyyy
  --conflict-file FILE   File to write conflicted paths to. Default: conflicted-files.txt
  --report-file FILE     Markdown conflict report file. Default: branch-sync-conflict.md
  --preserve-artifacts   Keep conflict/report files during dry-run
  --create-issue         Create a GitHub issue on conflict. Skipped in dry-run by default
  --issue-labels LABELS  Comma-separated labels for created issue
  --issue-assignee USER  GitHub username to assign when creating conflict issue
  --dry-run              Test the merge without pushing, deleting remote branches, or leaving artifacts
  --push                 Push changes. Overrides PUSH_CHANGES=false
  -h, --help             Show this help

Environment variables with matching names are also supported:
  SOURCE_BRANCH, BRANCH_PREFIX, REMOTE, KEEP_BRANCHES, DATE_FORMAT,
  CONFLICT_FILE, REPORT_FILE, PUSH_CHANGES, PRESERVE_ARTIFACTS,
  CREATE_ISSUE, ISSUE_LABELS, ISSUE_ASSIGNEE

Generated branch names use:
  <prefix>-<date>-<sequence>

Example:
  PUSH_CHANGES=false .github/scripts/sync-primary-to-upgrade.sh \
    --source main --prefix 2.0.0 --date-format mmddyyyy
USAGE
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --source)
      SOURCE_BRANCH="${2:?Missing value for --source}"
      shift 2
      ;;
    --prefix)
      BRANCH_PREFIX="${2:?Missing value for --prefix}"
      shift 2
      ;;
    --remote)
      REMOTE="${2:?Missing value for --remote}"
      shift 2
      ;;
    --keep)
      KEEP_BRANCHES="${2:?Missing value for --keep}"
      shift 2
      ;;
    --date-format)
      DATE_FORMAT="${2:?Missing value for --date-format}"
      shift 2
      ;;
    --conflict-file)
      CONFLICT_FILE="${2:?Missing value for --conflict-file}"
      shift 2
      ;;
    --report-file)
      REPORT_FILE="${2:?Missing value for --report-file}"
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
      echo "- Branch prefix: \`${BRANCH_PREFIX}\`"
      echo "- Remote: \`${REMOTE}\`"
      echo "- Keep branches: \`${KEEP_BRANCHES}\`"
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

conflict_issue_title() {
  local pr_number short_sha source_label

  pr_number="$(infer_pr_number)"
  short_sha="$(git rev-parse --short "${REMOTE}/${SOURCE_BRANCH}")"

  if [ "$pr_number" != "unknown" ]; then
    source_label="PR #${pr_number}"
  else
    source_label="${SOURCE_BRANCH}"
  fi

  printf 'Branch sync conflict: %s -> %s (%s)\n' "$source_label" "$new_branch" "$short_sha"
}

create_conflict_issue() {
  local issue_title label_args assignee_args label issue_status

  if [ "$CREATE_ISSUE" != "true" ]; then
    log "CREATE_ISSUE=false; GitHub issue creation skipped."
    return
  fi

  if [ "$PUSH_CHANGES" != "true" ] && [ "$ALLOW_DRY_RUN_ISSUE" != "true" ]; then
    log "Dry run detected; GitHub issue creation skipped."
    return
  fi

  command -v gh >/dev/null 2>&1 || fail "gh is required for --create-issue"

  issue_title="$(conflict_issue_title)"
  label_args=()
  assignee_args=()

  if [ -n "$ISSUE_ASSIGNEE" ]; then
    assignee_args+=(--assignee "$ISSUE_ASSIGNEE")
  fi

  IFS=',' read -ra labels <<< "$ISSUE_LABELS"
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

write_conflict_report() {
  local pr_number source_sha source_subject author run_url

  pr_number="$(infer_pr_number)"
  source_sha="$(git rev-parse "${REMOTE}/${SOURCE_BRANCH}")"
  source_subject="$(git log -1 --format='%s' "${REMOTE}/${SOURCE_BRANCH}")"
  author="$(developer_id)"
  run_url="$(workflow_run_url)"

  {
    echo "# Branch Sync Conflict"
    echo ""
    echo "Automatic branch sync could not merge \`${REMOTE}/${SOURCE_BRANCH}\` into a new \`${BRANCH_PREFIX}\` upgrade branch."
    echo ""
    echo "## Context"
    echo ""
    echo "- Source branch: \`${SOURCE_BRANCH}\`"
    echo "- Source commit: \`${source_sha}\`"
    echo "- Source commit subject: \`${source_subject}\`"
    echo "- Pull request: \`${pr_number}\`"
    echo "- Developer: \`${author}\`"
    echo "- Issue assignee: \`${ISSUE_ASSIGNEE:-not assigned}\`"
    echo "- Latest upgrade branch: \`${latest_branch}\`"
    echo "- Attempted new branch: \`${new_branch}\`"
    echo "- Workflow run: ${run_url}"
    echo "- Conflict file: \`${CONFLICT_FILE}\`"
    echo "- Report file: \`${REPORT_FILE}\`"
    echo ""
    echo "## Conflicted Files"
    echo ""
    sed 's/^/- `/' "$CONFLICT_FILE" | sed 's/$/`/'
    echo ""
    echo "## Artifacts"
    echo ""
    echo "The conflict file is \`${CONFLICT_FILE}\`. It contains the raw list of unresolved paths from:"
    echo ""
    echo '```bash'
    echo "git diff --name-only --diff-filter=U"
    echo '```'
    echo ""
    echo "When this runs in GitHub Actions, the workflow should upload \`${CONFLICT_FILE}\` and \`${REPORT_FILE}\` as artifacts on failure."
    echo "If a workflow run URL is available above, open that run and download the branch sync conflict report artifact."
    echo ""
    echo "## Manual Resolution"
    echo ""
    echo '```bash'
    echo "git fetch ${REMOTE}"
    echo "git switch --no-track -C ${new_branch} ${REMOTE}/${latest_branch}"
    echo "git merge ${REMOTE}/${SOURCE_BRANCH}"
    echo "# Resolve conflicts listed above"
    echo "git add <resolved-files>"
    echo "git commit"
    echo "git push ${REMOTE} HEAD:${new_branch}"
    echo '```'
    echo ""
    echo "After the resolved branch is pushed, treat \`${new_branch}\` as the latest \`${BRANCH_PREFIX}\` branch."
    echo ""
    echo "## Copilot Prompt"
    echo ""
    echo "Use this prompt with GitHub Copilot Workspace or Copilot Chat after checking out \`${new_branch}\` and reproducing the merge conflict:"
    echo ""
    echo '```text'
    echo "Resolve the merge conflict from merging ${REMOTE}/${SOURCE_BRANCH} into ${new_branch}."
    echo "Preserve the Spring Boot 4 / Spring AI 2.0 upgrade behavior from ${latest_branch}, and forward-port the relevant changes from ${SOURCE_BRANCH}."
    echo "Conflicted files are listed in ${CONFLICT_FILE}. After resolving, run the project tests that cover the changed modules."
    echo '```'
  } > "$REPORT_FILE"
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

date_sort_key() {
  case "$DATE_FORMAT" in
    mmddyyyy)
      printf '%s%s%s\n' "${1:4:4}" "${1:0:2}" "${1:2:2}"
      ;;
    yyyymmdd)
      printf '%s\n' "$1"
      ;;
  esac
}

generated_branch_records() {
  local branch rest date_part sequence sort_key

  git for-each-ref --format='%(refname:strip=3)' "refs/remotes/${REMOTE}/${BRANCH_PREFIX}-*" |
    while IFS= read -r branch; do
      [ "$branch" != "HEAD" ] || continue
      rest="${branch#"${BRANCH_PREFIX}-"}"

      if [[ "$rest" =~ ^([0-9]{8})(-([0-9]+))?$ ]]; then
        date_part="${BASH_REMATCH[1]}"
        sequence="${BASH_REMATCH[3]:-0}"
        sort_key="$(date_sort_key "$date_part")"
        printf '%s %09d %s\n' "$sort_key" "$sequence" "$branch"
      fi
    done
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

trim_old_remote_branches() {
  local branches_to_delete

  [ "$KEEP_BRANCHES" -gt 0 ] || fail "KEEP_BRANCHES must be greater than zero"

  branches_to_delete="$(
    generated_branch_records |
      sort -r |
      awk -v keep="$KEEP_BRANCHES" 'NR > keep { print $3 }'
  )"

  if [ -z "$branches_to_delete" ]; then
    log "No old ${BRANCH_PREFIX} branches to delete."
    return
  fi

  log "Old generated branches selected for deletion:"
  printf '%s\n' "$branches_to_delete"

  if [ "$PUSH_CHANGES" != "true" ]; then
    log "PUSH_CHANGES=false; remote branch deletion skipped."
    return
  fi

  while IFS= read -r branch; do
    [ -n "$branch" ] || continue
    git push "$REMOTE" --delete "$branch"
  done <<< "$branches_to_delete"
}

git rev-parse --is-inside-work-tree >/dev/null 2>&1 || fail "Not inside a git repository"
[[ "$KEEP_BRANCHES" =~ ^[0-9]+$ ]] || fail "KEEP_BRANCHES must be a positive integer"
require_clean_tree

ORIGINAL_BRANCH="$(git branch --show-current || true)"
ORIGINAL_SHA="$(git rev-parse HEAD)"

log "Fetching ${REMOTE}/${SOURCE_BRANCH} and ${BRANCH_PREFIX} rolling branches."

git fetch --prune "$REMOTE" \
  "+refs/heads/${SOURCE_BRANCH}:refs/remotes/${REMOTE}/${SOURCE_BRANCH}" \
  "+refs/heads/${BRANCH_PREFIX}-*:refs/remotes/${REMOTE}/${BRANCH_PREFIX}-*"

latest_record="$(generated_branch_records | sort -r | head -n 1 || true)"
[ -n "$latest_record" ] || fail "No remote branches found matching ${BRANCH_PREFIX}-<date>[-sequence]"

latest_branch="${latest_record#* * }"
latest_branch="${latest_branch#* }"
today_part="$(today_for_branch)"
today_sort_key="$(date_sort_key "$today_part")"
next_sequence=1

while IFS= read -r record; do
  record_sort_key="${record%% *}"
  record_remainder="${record#* }"
  record_sequence="${record_remainder%% *}"

  if [ "$record_sort_key" = "$today_sort_key" ] && [ "$record_sequence" -ge "$next_sequence" ]; then
    next_sequence=$((10#$record_sequence + 1))
  fi
done < <(generated_branch_records)

new_branch="${BRANCH_PREFIX}-${today_part}-${next_sequence}"

while git show-ref --verify --quiet "refs/remotes/${REMOTE}/${new_branch}"; do
  next_sequence=$((next_sequence + 1))
  new_branch="${BRANCH_PREFIX}-${today_part}-${next_sequence}"
done

log "Latest upgrade branch: ${REMOTE}/${latest_branch}"
log "New upgrade branch: ${new_branch}"
log "Merging ${REMOTE}/${SOURCE_BRANCH} into ${new_branch}"

git switch --no-track -C "$new_branch" "${REMOTE}/${latest_branch}"
require_clean_tree

before_sha="$(git rev-parse HEAD)"

set +e
git merge --no-edit "${REMOTE}/${SOURCE_BRANCH}"
merge_status=$?
set -e

if [ "$merge_status" -ne 0 ]; then
  log "Merge conflict detected."

  git diff --name-only --diff-filter=U | sort > "$CONFLICT_FILE"
  write_conflict_report

  log "Conflicted files:"
  cat "$CONFLICT_FILE"
  log "Conflict report:"
  log "$REPORT_FILE"

  write_summary "Merge conflict detected while merging \`${SOURCE_BRANCH}\` into \`${new_branch}\`. See \`${REPORT_FILE}\` for resolution instructions."
  create_conflict_issue

  git merge --abort || true
  restore_original_branch
  delete_local_branch_if_present "$new_branch"

  if [ "$PUSH_CHANGES" != "true" ] && [ "$PRESERVE_ARTIFACTS" != "true" ]; then
    rm -f "$CONFLICT_FILE" "$REPORT_FILE"
  fi

  exit 2
fi

after_sha="$(git rev-parse HEAD)"

if [ "$before_sha" = "$after_sha" ]; then
  log "Latest upgrade branch already contains ${SOURCE_BRANCH}; no new branch needed."
  write_summary "No changes needed. \`${latest_branch}\` already contains \`${SOURCE_BRANCH}\`."
  restore_original_branch
  delete_local_branch_if_present "$new_branch"
  exit 0
fi

log "Merge completed:"
git --no-pager log --oneline --decorate -n 5

if [ "$PUSH_CHANGES" != "true" ]; then
  log "PUSH_CHANGES=false; dry run complete."
  write_summary "Dry run succeeded. Merge was clean; \`${new_branch}\` was not pushed."
  restore_original_branch
  delete_local_branch_if_present "$new_branch"
  exit 0
fi

git push "$REMOTE" "HEAD:${new_branch}"
git fetch "$REMOTE" "+refs/heads/${new_branch}:refs/remotes/${REMOTE}/${new_branch}"
trim_old_remote_branches

write_summary "Merge succeeded. Pushed \`${new_branch}\` and trimmed generated branches to the latest \`${KEEP_BRANCHES}\`."
log "Pushed ${new_branch}."
restore_original_branch
delete_local_branch_if_present "$new_branch"
