#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
log()    { echo -e "${GREEN}[OK]${NC} $1"; }
warn()   { echo -e "${YELLOW}[WARN]${NC} $1"; }
fail()   { echo -e "${RED}[FAIL]${NC} $1"; exit 1; }
info()   { echo -e "${CYAN}[INFO]${NC} $1"; }
header() { echo -e "\n${CYAN}════════════════════════════════════════════${NC}"; echo -e "${CYAN}  $1${NC}"; echo -e "${CYAN}════════════════════════════════════════════${NC}"; }

# ── Config ────────────────────────────────────────────────────────────────
VERSION_ARG=""
BUMP="patch"
MODULE=""
PUSH=true
GIT_TAG=false
DRY_RUN=false

usage() {
    echo "Usage: $0 [bump-type] [options]"
    echo ""
    echo "  bump-type   patch (default) | minor | major   semver component to increment"
    echo ""
    echo "Options:"
    echo "  -v, --version X.Y.Z   Set an explicit version instead of auto-bumping"
    echo "  -m, --module NAME     Only build/push one module (e.g. layer0-server)"
    echo "      --no-push         Build docker images locally but do not push"
    echo "  -g, --git-tag         Commit the version bump and create annotated git tag v<version>"
    echo "      --dry-run         Print what would change without modifying anything"
    echo "  -h, --help            Show this help"
    echo ""
    echo "Environment: REGISTRY (ghcr.io), OWNER (bigt-ai-platform), GITHUB_TOKEN"
    echo ""
    echo "Example: $0 minor          # 0.6.0 -> 0.7.0, build & push images"
    echo "         $0 -v 0.6.1 -m layer0-server"
    exit 0
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        patch|minor|major) BUMP="$1"; shift ;;
        -v|--version)      VERSION_ARG="$2"; shift 2 ;;
        -m|--module)       MODULE="$2"; shift 2 ;;
        --no-push)         PUSH=false; shift ;;
        -g|--git-tag)      GIT_TAG=true; shift ;;
        --dry-run)         DRY_RUN=true; shift ;;
        -h|--help)         usage ;;
        *)                 echo "Unknown option: $1"; usage ;;
    esac
done

# ── Read current version from the root pom ────────────────────────────────
read_version() {
    awk '/<artifactId>bigtangle<\/artifactId>/{f=1} f && /<version>[0-9]+\.[0-9]+\.[0-9]+<\/version>/{gsub(/.*<version>|<\/version>.*/, ""); print; exit}' pom.xml
}

semver_valid() { [[ "$1" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; }

compute_version() {
    local v="$1" bump="$2"
    local IFS='.'; read -ra parts <<< "$v"
    local major="${parts[0]}" minor="${parts[1]}" patch="${parts[2]}"
    case "$bump" in
        major) major=$((major+1)); minor=0; patch=0 ;;
        minor) minor=$((minor+1)); patch=0 ;;
        patch) patch=$((patch+1)) ;;
    esac
    echo "$major.$minor.$patch"
}

CUR="$(read_version)"
[[ -n "$CUR" ]] || fail "Could not determine current version from pom.xml"

if [[ -n "$VERSION_ARG" ]]; then
    semver_valid "$VERSION_ARG" || fail "Invalid version: $VERSION_ARG (expected X.Y.Z)"
    NEW="$VERSION_ARG"
    [[ "$NEW" != "$CUR" ]] || fail "New version equals current version ($CUR)"
else
    NEW="$(compute_version "$CUR" "$BUMP")"
fi

log "Version: $CUR -> $NEW"

# ── Files that embed the version ──────────────────────────────────────────
# All pom.xml + Dockerfile under version control, plus helper/deploy.sh.
mapfile -t FILES < <(git ls-files | grep -E '(pom\.xml|Dockerfile)$')
FILES+=("helper/deploy.sh")

changed=()
for f in "${FILES[@]}"; do
    if grep -q "$CUR" "$f"; then
        changed+=("$f")
    fi
done

if [[ "${#changed[@]}" -eq 0 ]]; then
    fail "No files reference version $CUR to update"
fi

info "Files to update (${#changed[@]}):"
printf '  %s\n' "${changed[@]}"

if [[ "$DRY_RUN" = true ]]; then
    info "Dry run — no files modified."
    exit 0
fi

# ── Apply version bump ────────────────────────────────────────────────────
header "Bumping version to $NEW"
for f in "${changed[@]}"; do
    perl -pi -e "s/\Q$CUR\E/$NEW/g" "$f"
done
log "Updated ${#changed[@]} files"
info "Changed files:"
git diff --stat -- "${changed[@]}"

# ── Optional commit + git tag ─────────────────────────────────────────────
if [[ "$GIT_TAG" = true ]]; then
    header "Committing and tagging v$NEW"
    if ! git diff --quiet; then
        git add -A
        git commit -m "release: bump version to $NEW"
        log "Committed version bump"
    else
        warn "No changes to commit (version files already committed)"
    fi
    if git rev-parse "v$NEW" >/dev/null 2>&1; then
        warn "Tag v$NEW already exists; skipping tag creation"
    else
        git tag -a "v$NEW" -m "Release v$NEW"
        log "Created annotated tag v$NEW"
    fi
fi

# ── Build and publish docker images ───────────────────────────────────────
header "Building docker images"
DEPLOY_ARGS=()
[[ "$PUSH" = true ]] && DEPLOY_ARGS+=("-p")
[[ -n "$MODULE" ]] && DEPLOY_ARGS+=("-m" "$MODULE")

if [[ "$DRY_RUN" != true ]]; then
    TAG="$NEW" "${ROOT}/helper/deploy.sh" "${DEPLOY_ARGS[@]}"
else
    info "Dry run — deploy skipped"
fi

if [[ "$PUSH" = true ]]; then
    log "Done. Version $NEW built and pushed: ${REGISTRY:-ghcr.io}/${OWNER:-bigt-ai-platform}/<module>:$NEW (+ :latest)"
else
    log "Done. Version $NEW built locally (not pushed): ${REGISTRY:-ghcr.io}/${OWNER:-bigt-ai-platform}/<module>:$NEW"
fi
if [[ "$GIT_TAG" = true ]]; then
    info "Git tag v$NEW is local only. Push it with: git push origin v$NEW"
fi
