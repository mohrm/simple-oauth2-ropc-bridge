#!/usr/bin/env bash
set -euo pipefail

# Find a maximal compatible subset of Renovate branches by repeatedly testing
# merge combinations on an isolated temporary branch.

BASE_BRANCH="main"
CACHE_FILE=".build-cache"
FILTER_PATTERN=""
CREATE_FINAL_BRANCH=""

TEMP_BRANCH=""
ORIGINAL_REF=""

log() {
  printf '[%s] %s\n' "$(date -u +'%Y-%m-%dT%H:%M:%SZ')" "$*"
}

usage() {
  cat <<'USAGE'
Usage: ./scripts/select-renovate-max-set.sh [options] [filter]

Options:
  --base <branch>          Base branch used for all tests (default: main)
  --cache <file>           Cache file path (default: .build-cache)
  --create-final <branch>  Create/update this branch with final selected merges
  -h, --help               Show this help

Arguments:
  filter                   Optional grep pattern used to filter candidate branches
USAGE
}

run_build() {
  # Repository-specific default (Maven Java project).
  # Override this function if your CI/local workflow needs different commands.
  mvn -B -q test
}

die() {
  log "ERROR: $*"
  exit 1
}

require_clean_repo() {
  if [[ -n "$(git status --porcelain)" ]]; then
    die "Repository is not clean. Commit/stash changes first."
  fi
}

require_base_branch() {
  if ! git show-ref --verify --quiet "refs/heads/${BASE_BRANCH}"; then
    if git show-ref --verify --quiet "refs/remotes/origin/${BASE_BRANCH}"; then
      log "Creating local ${BASE_BRANCH} from origin/${BASE_BRANCH}."
      git branch "${BASE_BRANCH}" "origin/${BASE_BRANCH}" >/dev/null
    elif [[ "${BASE_BRANCH}" == "main" ]] && git show-ref --verify --quiet "refs/heads/master"; then
      log "Base branch main missing; falling back to master."
      BASE_BRANCH="master"
    else
      die "Base branch '${BASE_BRANCH}' not found locally or on origin."
    fi
  fi
}

init_cache() {
  touch "${CACHE_FILE}"
}

cleanup_test_state() {
  git reset --hard >/dev/null
  git clean -fd >/dev/null
}

cleanup() {
  set +e
  if [[ -n "${ORIGINAL_REF}" ]]; then
    git checkout -q "${ORIGINAL_REF}" 2>/dev/null
  fi
  if [[ -n "${TEMP_BRANCH}" ]] && git show-ref --verify --quiet "refs/heads/${TEMP_BRANCH}"; then
    git branch -D "${TEMP_BRANCH}" >/dev/null 2>&1
  fi
}

normalize_and_hash_combo() {
  local combo=("$@")
  if ((${#combo[@]} == 0)); then
    printf 'EMPTY'
    return
  fi
  printf '%s\n' "${combo[@]}" | sort | sha256sum | awk '{print $1}'
}

cache_get() {
  local key="$1"
  if [[ -f "${CACHE_FILE}" ]]; then
    awk -v k="${key}" '$1==k {print $2; found=1; exit} END {if(!found) print ""}' "${CACHE_FILE}"
  fi
}

cache_put() {
  local key="$1"
  local value="$2"
  printf '%s %s\n' "${key}" "${value}" >>"${CACHE_FILE}"
}

prepare_temp_branch() {
  git checkout -q "${BASE_BRANCH}"
  git checkout -q -B "${TEMP_BRANCH}" "${BASE_BRANCH}"
  cleanup_test_state
}

merge_branches_no_commit() {
  local branches=("$@")
  local b
  for b in "${branches[@]}"; do
    log "Merging ${b}"
    if ! git merge --no-ff --no-commit "${b}" >/dev/null 2>&1; then
      log "Merge conflict while merging ${b}"
      git merge --abort >/dev/null 2>&1 || true
      return 1
    fi
  done
  return 0
}

test_combination() {
  local combo=("$@")
  local key cached
  key="$(normalize_and_hash_combo "${combo[@]}")"
  cached="$(cache_get "${key}")"
  if [[ -n "${cached}" ]]; then
    log "Cache hit for combination (${#combo[@]} branches): ${cached}"
    [[ "${cached}" == "PASS" ]]
    return
  fi

  log "Testing combination with ${#combo[@]} branches"
  prepare_temp_branch

  if ! merge_branches_no_commit "${combo[@]}"; then
    cache_put "${key}" "FAIL"
    cleanup_test_state
    return 1
  fi

  if run_build; then
    cache_put "${key}" "PASS"
    cleanup_test_state
    return 0
  else
    cache_put "${key}" "FAIL"
    cleanup_test_state
    return 1
  fi
}

cluster_name_for_branch() {
  local branch="$1"
  shopt -s nocasematch
  if [[ "${branch}" =~ major ]]; then
    printf 'major'
  elif [[ "${branch}" =~ minor ]]; then
    printf 'minor'
  elif [[ "${branch}" =~ patch ]]; then
    printf 'patch'
  else
    printf 'other'
  fi
  shopt -u nocasematch
}

collect_candidates() {
  local raw branches
  raw="$(git branch -r | sed 's/^\s*//' | grep 'renovate/' || true)"
  if [[ -z "${raw}" ]]; then
    die "No remote renovate branches found."
  fi

  # Exclude symbolic refs like origin/HEAD -> origin/main.
  branches="$(printf '%s\n' "${raw}" | grep -v -- '->' || true)"

  if [[ -n "${FILTER_PATTERN}" ]]; then
    branches="$(printf '%s\n' "${branches}" | grep -E "${FILTER_PATTERN}" || true)"
  fi

  if [[ -z "${branches}" ]]; then
    die "No candidate branches left after filtering."
  fi

  mapfile -t CANDIDATES < <(printf '%s\n' "${branches}")
}

declare -a SELECTED=()
declare -a EXCLUDED=()

test_with_selected_and() {
  local addition=("$@")
  local combo=("${SELECTED[@]}" "${addition[@]}")
  test_combination "${combo[@]}"
}

resolve_conflicts() {
  local cluster=("$@")
  local n=${#cluster[@]}

  if ((n == 0)); then
    return
  fi

  if ((n == 1)); then
    local single="${cluster[0]}"
    log "Trying single branch fallback: ${single}"
    if test_with_selected_and "${single}"; then
      SELECTED+=("${single}")
      log "Included ${single}"
    else
      EXCLUDED+=("${single}")
      log "Excluded ${single}"
    fi
    return
  fi

  local mid=$((n / 2))
  local first=("${cluster[@]:0:mid}")
  local second=("${cluster[@]:mid}")

  log "Testing first half of size ${#first[@]}"
  if test_with_selected_and "${first[@]}"; then
    log "First half succeeded; including and recursing into second half"
    SELECTED+=("${first[@]}")
    resolve_conflicts "${second[@]}"
  else
    log "First half failed; recursing into first half only"
    resolve_conflicts "${first[@]}"
  fi
}

create_final_branch() {
  local target="$1"
  log "Creating final branch ${target} from ${BASE_BRANCH}"
  git checkout -q "${BASE_BRANCH}"
  git checkout -q -B "${target}" "${BASE_BRANCH}"
  cleanup_test_state

  if ((${#SELECTED[@]} == 0)); then
    log "No selected branches to merge into final branch"
    return
  fi

  if ! merge_branches_no_commit "${SELECTED[@]}"; then
    die "Unexpected conflict while creating final branch."
  fi

  git commit -m "Merge selected Renovate branches" >/dev/null
  log "Final branch ${target} created with ${#SELECTED[@]} merges"
}

main() {
  while (($# > 0)); do
    case "$1" in
      --base)
        BASE_BRANCH="$2"
        shift 2
        ;;
      --cache)
        CACHE_FILE="$2"
        shift 2
        ;;
      --create-final)
        CREATE_FINAL_BRANCH="$2"
        shift 2
        ;;
      -h|--help)
        usage
        exit 0
        ;;
      *)
        FILTER_PATTERN="$1"
        shift
        ;;
    esac
  done

  require_clean_repo
  require_base_branch
  init_cache

  ORIGINAL_REF="$(git rev-parse --abbrev-ref HEAD)"
  TEMP_BRANCH="test-combination-$$"
  trap cleanup EXIT INT TERM

  collect_candidates
  log "Found ${#CANDIDATES[@]} candidate branches"

  local -a PATCH_CLUSTER=()
  local -a MINOR_CLUSTER=()
  local -a MAJOR_CLUSTER=()
  local -a OTHER_CLUSTER=()

  local b cluster
  for b in "${CANDIDATES[@]}"; do
    cluster="$(cluster_name_for_branch "${b}")"
    case "${cluster}" in
      patch) PATCH_CLUSTER+=("${b}") ;;
      minor) MINOR_CLUSTER+=("${b}") ;;
      major) MAJOR_CLUSTER+=("${b}") ;;
      *) OTHER_CLUSTER+=("${b}") ;;
    esac
  done

  local -a CLUSTER_NAMES=(patch minor major other)
  local name
  for name in "${CLUSTER_NAMES[@]}"; do
    local -a current=()
    case "${name}" in
      patch) current=("${PATCH_CLUSTER[@]}") ;;
      minor) current=("${MINOR_CLUSTER[@]}") ;;
      major) current=("${MAJOR_CLUSTER[@]}") ;;
      other) current=("${OTHER_CLUSTER[@]}") ;;
    esac

    if ((${#current[@]} == 0)); then
      continue
    fi

    log "Processing cluster '${name}' with ${#current[@]} branches"
    if test_with_selected_and "${current[@]}"; then
      SELECTED+=("${current[@]}")
      log "Cluster '${name}' fully included"
    else
      log "Cluster '${name}' failed; resolving recursively"
      resolve_conflicts "${current[@]}"
    fi
  done

  if ((${#EXCLUDED[@]} > 0)); then
    log "Opportunistic pass on ${#EXCLUDED[@]} excluded branches"
    local -a still_excluded=()
    for b in "${EXCLUDED[@]}"; do
      if test_with_selected_and "${b}"; then
        SELECTED+=("${b}")
        log "Opportunistic include: ${b}"
      else
        still_excluded+=("${b}")
      fi
    done
    EXCLUDED=("${still_excluded[@]}")
  fi

  printf '\nSelected branches (%d):\n' "${#SELECTED[@]}"
  printf '  %s\n' "${SELECTED[@]}"

  if ((${#EXCLUDED[@]} > 0)); then
    printf '\nExcluded branches (%d):\n' "${#EXCLUDED[@]}"
    printf '  %s\n' "${EXCLUDED[@]}"
  fi

  if [[ -n "${CREATE_FINAL_BRANCH}" ]]; then
    create_final_branch "${CREATE_FINAL_BRANCH}"
  fi

  log "Done"
}

main "$@"
