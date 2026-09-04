#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
log()    { echo -e "${GREEN}[OK]${NC} $1"; }
fail()   { echo -e "${RED}[FAIL]${NC} $1"; exit 1; }
info()   { echo -e "${YELLOW}[INFO]${NC} $1"; }
header() { echo -e "\n${CYAN}════════════════════════════════════════════${NC}"; echo -e "${CYAN}  $1${NC}"; echo -e "${CYAN}════════════════════════════════════════════${NC}"; }

# ── Config ────────────────────────────────────────────────────────────────
REGISTRY="${REGISTRY:-ghcr.io}"
OWNER="${OWNER:-bigt-ai-platform}"
PUSH=false
VERSION="${TAG:-latest}"
JAVA_VERSION="${JAVA_VERSION:-25}"
MVN_VERSION="0.6.2"

# Modules to build (layer0 + L1 servers)
declare -A MODULES
MODULES["layer0-server"]="layer0-server/Dockerfile"
MODULES["l1-pai-server"]="l1-pai-server/Dockerfile"
MODULES["l1-order-server"]="l1-order-server/Dockerfile"
MODULES["l1-nft-server"]="l1-nft-server/Dockerfile"
MODULES["l1-payment-server"]="l1-payment-server/Dockerfile"
MODULES["l1-contract-server"]="l1-contract-server/Dockerfile"
MODULES["l1-evm-server"]="l1-evm-server/Dockerfile"
MODULES["l1-social-server"]="l1-social-server/Dockerfile"

usage() {
    echo "Usage: $0 [options]"
    echo ""
    echo "  -p, --push       Push images to registry (default: local build only)"
    echo "  -m, --module M   Build only specific module (default: all)"
    echo "  -h, --help       Show this help"
    echo ""
    echo "Environment: REGISTRY, OWNER, TAG, GITHUB_TOKEN"
    exit 0
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        -p|--push)  PUSH=true; shift ;;
        -m|--module) MODULE="$2"; shift 2 ;;
        -h|--help)  usage ;;
        *)          echo "Unknown option: $1"; usage ;;
    esac
done

# ── Login ──────────────────────────────────────────────────────────────────
if [ "$PUSH" = true ]; then
    header "Login to $REGISTRY"
    if [ -n "${GITHUB_TOKEN:-}" ]; then
        echo "$GITHUB_TOKEN" | docker login "$REGISTRY" -u "${GITHUB_ACTOR:-$OWNER}" --password-stdin
        log "Logged in via GITHUB_TOKEN"
    else
        docker login "$REGISTRY"
    fi
fi

# ── Build all modules ──────────────────────────────────────────────────────
header "Building all modules with Maven"
MAVEN_PLUGINS="-pl layer0-server,l1-pai-server,l1-order-server,l1-nft-server,l1-payment-server,l1-contract-server,l1-evm-server,l1-social-server"
if [ -n "${MODULE:-}" ]; then
    MAVEN_PLUGINS="-pl $MODULE"
fi
mvn package -Dmaven.test.skip=true -q -f "$ROOT/pom.xml" -am $MAVEN_PLUGINS
log "Maven build complete"

# ── Build Docker images ────────────────────────────────────────────────────
header "Building Docker images"
for module in "${!MODULES[@]}"; do
    dockerfile="${MODULES[$module]}"
    [ -n "${MODULE:-}" ] && [ "$module" != "$MODULE" ] && continue

    IMAGE="$REGISTRY/$OWNER/$module"
    TAG="$VERSION"

    info "Building $IMAGE:$TAG"
    docker build -t "$IMAGE:$TAG" -t "$IMAGE:latest" \
        -f "$ROOT/$dockerfile" \
        "$ROOT/$module"

    log "Built $module"
done

# ── Push images ────────────────────────────────────────────────────────────
if [ "$PUSH" = true ]; then
    header "Pushing to $REGISTRY"
    for module in "${!MODULES[@]}"; do
        dockerfile="${MODULES[$module]}"
        [ -n "${MODULE:-}" ] && [ "$module" != "$MODULE" ] && continue

        IMAGE="$REGISTRY/$OWNER/$module"
        TAG="$VERSION"

        info "Pushing $IMAGE:$TAG"
        docker push "$IMAGE:$TAG"
        docker push "$IMAGE:latest"
        log "Pushed $module"
    done
    log "All images built and pushed successfully"
else
    log "Local build only (use -p to push). Images: $REGISTRY/$OWNER/<module>:$VERSION"
fi
