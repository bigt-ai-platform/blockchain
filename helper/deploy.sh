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
# Docker tag uses git commit hash; override with TAG env var for releases
VERSION="${TAG:-$(git log -1 --format=%h)}"
JAVA_VERSION="${JAVA_VERSION:-25}"
MVN_VERSION="0.6.0"

# Modules to build (layer0 + L1 servers)
declare -A MODULES
MODULES["layer0-server"]="layer0-server/Dockerfile"
MODULES["l1-pai-server"]="l1-pai-server/Dockerfile"
MODULES["l1-order-server"]="l1-order-server/Dockerfile"
MODULES["l1-nft-server"]="l1-nft-server/Dockerfile"
MODULES["l1-payment-server"]="l1-payment-server/Dockerfile"
MODULES["l1-contract-server"]="l1-contract-server/Dockerfile"
MODULES["layer0-mcmc"]="layer0-mcmc/Dockerfile"
MODULES["l1-pai-mcmc"]="l1-pai-mcmc/Dockerfile"
MODULES["l1-order-mcmc"]="l1-order-mcmc/Dockerfile"
MODULES["l1-contract-mcmc"]="l1-contract-mcmc/Dockerfile"
MODULES["l1-payment-mcmc"]="l1-payment-mcmc/Dockerfile"


# ── Login ──────────────────────────────────────────────────────────────────
header "Login to $REGISTRY"
if [ -n "${GITHUB_TOKEN:-}" ]; then
    echo "$GITHUB_TOKEN" | docker login "$REGISTRY" -u "${GITHUB_ACTOR:-$OWNER}" --password-stdin
    log "Logged in via GITHUB_TOKEN"
else
    docker login "$REGISTRY"
fi

# ── Build all modules ──────────────────────────────────────────────────────
header "Building all modules with Maven"
mvn clean install -DskipTests -q -f "$ROOT/pom.xml" -am \
  -pl layer0-server,layer0-mcmc,l1-pai-server,l1-pai-mcmc,l1-order-server,l1-order-mcmc,l1-nft-server,l1-payment-server,l1-payment-mcmc,l1-contract-server,l1-contract-mcmc

# ── Create temporary Dockerfiles for modules that don't have one ───────────
create_dockerfile() {
    local module="$1"
    local dockerfile="$module/Dockerfile"
    if [ -f "$dockerfile" ]; then
        log "Dockerfile exists for $module"
        return
    fi
    info "Creating Dockerfile for $module"
    mkdir -p "$module"
    # Find the executable JAR (prefer -exec classifier, fall back to plain jar)
    local jar
    jar=$(ls "$ROOT/$module/target/"*-exec.jar 2>/dev/null | head -1)
    if [ -z "$jar" ]; then
        jar=$(ls "$ROOT/$module/target/"*.jar 2>/dev/null | grep -v "sources\|javadoc\|original" | head -1)
    fi
    if [ -z "$jar" ]; then
        fail "No executable JAR found in $ROOT/$module/target/"
    fi
    local jar_name
    jar_name=$(basename "$jar")
    cat > "$dockerfile" << EOF
FROM eclipse-temurin:${JAVA_VERSION}-jre-alpine
WORKDIR /app
COPY target/$jar_name app.jar
EXPOSE 8080
ENTRYPOINT ["java", "--add-exports", "java.base/sun.nio.ch=ALL-UNNAMED", "--add-exports", "java.base/java.lang=ALL-UNNAMED", "-jar", "app.jar"]
EOF
}

# ── Build Docker images ────────────────────────────────────────────────────
header "Building Docker images"
for module in "${!MODULES[@]}"; do
    dockerfile="${MODULES[$module]}"
    create_dockerfile "$module"

    IMAGE="$REGISTRY/$OWNER/$module"
    TAG="$VERSION"

    info "Building $IMAGE:$TAG"
    docker build -t "$IMAGE:$TAG" -t "$IMAGE:latest" \
        -f "$ROOT/$dockerfile" \
        --build-arg JAVA_VERSION="$JAVA_VERSION" \
        "$ROOT/$module"

    log "Built $IMAGE:$TAG"
done

# ── Push images ────────────────────────────────────────────────────────────
header "Pushing to $REGISTRY"
for module in "${!MODULES[@]}"; do
    IMAGE="$REGISTRY/$OWNER/$module"
    TAG="$VERSION"

    info "Pushing $IMAGE:$TAG"
    docker push "$IMAGE:$TAG"
    docker push "$IMAGE:latest"
    log "Pushed $IMAGE:$TAG"
done

header "Deploy complete"
echo ""
echo "  Registry: $REGISTRY/$OWNER"
echo "  Version:  $VERSION"
echo ""
for module in "${!MODULES[@]}"; do
    printf "  %-25s %s/%s:%s\n" "$module" "$REGISTRY" "$OWNER" "$VERSION"
done
echo ""
log "All images built and pushed successfully"
