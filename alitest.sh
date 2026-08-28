#!/usr/bin/env bash
# alitest.sh — create the best Aliyun ECS instance, run ConfirmedPaymentBenchmark
# for max TPS, print the result and SHUT DOWN the instance when finished.
#
# Flow:
#   1. create (or reuse via ALI_INSTANCE_ID) a top-tier ECS instance
#   2. provision: JDK 25, PostgreSQL, maven; rsync this repo
#   3. build reactor deps, run ConfirmedPaymentBenchmark with the best-known
#      max-resource config (docs/performance.md 2026-08-28 campaign, run 1)
#   4. report Submit/CONFIRMED TPS, copy the full log back to logs/
#   5. stop the instance (KEEP=true to leave it running, RELEASE=true to delete)
#
# Usage: ./alitest.sh [options]
#       --profile NAME    aliyun CLI profile        (default $ALI_PROFILE, active: china)
#       --region ID       region id                 (default $ALI_REGION, cn-hangzhou)
#       --type IT         instance type             (default $ALI_INSTANCE_TYPE)
#       --instance-id ID  reuse an already-created instance instead of creating one
#       --keypair NAME    existing SSH key pair     (default: create a fresh one)
#       --key-file F      private key for --instance-id mode (default ~/.ssh/id_rsa)
#       --tx N            benchmark payments        (default 200000)
#       --clients N       submit concurrency        (default 32)
#       --batch N         tx per submit call        (default 250)
#       --slot-ms MS      pos.slotIntervalMs        (default 1000)
#       --tx-per-block N  batch.txPerBlock          (default 2000)
#       --heap SIZE       benchmark JVM heap        (default 64g)
#       --keep            do NOT shut the instance down when finished
#       --release         DELETE the instance when finished instead of stopping
# Env: ALI_PROFILE ALI_REGION ALI_INSTANCE_TYPE ALI_VSWITCH_ID ALI_SECURITY_GROUP_ID
#      ALI_SSH_CIDR (default 0.0.0.0/0) ALI_INSTANCE_ID KEEP RELEASE
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$SCRIPT_DIR"
STATE_DIR="${STATE_DIR:-/tmp/opencode}"
mkdir -p "$STATE_DIR"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
log()    { echo -e "${GREEN}[OK]${NC} $1"; }
fail()   { echo -e "${RED}[FAIL]${NC} $1"; exit 1; }
info()   { echo -e "${YELLOW}[INFO]${NC} $1"; }
header() { echo -e "\n${CYAN}════════════════════════════════════════════${NC}"; echo -e "${CYAN}  $1${NC}"; echo -e "${CYAN}════════════════════════════════════════════${NC}"; }

# ── Defaults: best server + best-known max-TPS config ────────────────────────
ALI_PROFILE="${ALI_PROFILE:-china}"                 # active profile (cn-hangzhou)
ALI_REGION="${ALI_REGION:-cn-hangzhou}"
ALI_INSTANCE_TYPE="${ALI_INSTANCE_TYPE:-ecs.g8i.16xlarge}"  # 64 vCPU / 256 GiB — best general-purpose current gen
ALI_IMAGE_ID="${ALI_IMAGE_ID:-}"                    # auto-resolved: latest ubuntu_24_04 system image
ALI_VSWITCH_ID="${ALI_VSWITCH_ID:-}"
ALI_SECURITY_GROUP_ID="${ALI_SECURITY_GROUP_ID:-}"
ALI_SSH_CIDR="${ALI_SSH_CIDR:-0.0.0.0/0}"
ALI_BANDWIDTH="${ALI_BANDWIDTH:-100}"   # Mbps public out; some accounts cap at 100
ALI_INSTANCE_ID="${ALI_INSTANCE_ID:-}"
ALI_KEYPAIR="${ALI_KEYPAIR:-}"
ALI_KEY_FILE="${ALI_KEY_FILE:-$HOME/.ssh/id_rsa}"

# Benchmark: docs/performance.md run 1 (best confirmed TPS) — heap scaled up
# for the bigger machine, every 200k tx confirmed on-chain.
TX="${TX:-200000}"
CLIENTS="${CLIENTS:-32}"
BATCH="${BATCH:-250}"
MIN_TX="${MIN_TX:-3000}"
MAX_AGE="${MAX_AGE:-1500}"
SLOT_MS="${SLOT_MS:-1000}"
TX_PER_BLOCK="${TX_PER_BLOCK:-2000}"
MEMPOOL="${MEMPOOL:-200000}"   # server.mempoolMaxTx — must hold the whole burst,
                               # else MempoolFullException kills the clients (performance.md)
HEAP="${HEAP:-64g}"
CONFIRM_TIMEOUT="${CONFIRM_TIMEOUT:-1800}"

KEEP="${KEEP:-false}"
RELEASE="${RELEASE:-false}"
STAMP="$(date +%Y%m%d-%H%M%S)"
INSTANCE_NAME="alitest-tps-$STAMP"
LOG_DIR="$ROOT/logs"; mkdir -p "$LOG_DIR"
BENCH_LOG="$LOG_DIR/alitest-$STAMP.bench.log"

usage() { sed -n '2,27p' "$0" | sed 's/^# \{0,1\}//'; exit 0; }

while [[ $# -gt 0 ]]; do
    case "$1" in
        --profile)      ALI_PROFILE="$2"; shift 2 ;;
        --region)       ALI_REGION="$2"; shift 2 ;;
        --type)         ALI_INSTANCE_TYPE="$2"; shift 2 ;;
        --instance-id)  ALI_INSTANCE_ID="$2"; shift 2 ;;
        --keypair)      ALI_KEYPAIR="$2"; shift 2 ;;
        --key-file)     ALI_KEY_FILE="$2"; shift 2 ;;
        --tx)           TX="$2"; shift 2 ;;
        --clients)      CLIENTS="$2"; shift 2 ;;
        --batch)        BATCH="$2"; shift 2 ;;
        --slot-ms)      SLOT_MS="$2"; shift 2 ;;
        --tx-per-block) TX_PER_BLOCK="$2"; shift 2 ;;
        --mempool)      MEMPOOL="$2"; shift 2 ;;
        --heap)         HEAP="$2"; shift 2 ;;
        --keep)         KEEP=true; shift ;;
        --release)      RELEASE=true; shift ;;
        -h|--help)      usage ;;
        *) echo "Unknown option: $1"; usage ;;
    esac
done

for c in aliyun jq rsync ssh scp; do
    command -v "$c" >/dev/null || fail "$c not found — install it first"
done
aliyun configure list --profile "$ALI_PROFILE" >/dev/null 2>&1 \
    || fail "aliyun CLI profile '$ALI_PROFILE' not configured (aliyun configure --profile $ALI_PROFILE)"

IID="" PEM="" KP=""

# ── Shutdown guarantee: stop (or release) the instance on ANY exit ───────────
cleanup() {
    local rc=$?
    trap - EXIT
    if [ -n "$IID" ]; then
        if [ "$KEEP" = true ]; then
            info "KEEP=true — instance $IID left running (ssh root@$(cat "$STATE_DIR/alitest.ip" 2>/dev/null || echo '<ip>'))"
        elif [ "$RELEASE" = true ]; then
            info "releasing instance $IID ..."
            aliyun --profile "$ALI_PROFILE" ecs StopInstance --RegionId "$ALI_REGION" --InstanceId "$IID" >/dev/null 2>&1 || true
            for i in $(seq 1 24); do
                st=$(aliyun --profile "$ALI_PROFILE" ecs DescribeInstances --RegionId "$ALI_REGION" \
                    --InstanceIds "[\"$IID\"]" 2>/dev/null | jq -r '.Instances.Instance[0].Status' 2>/dev/null) || st=""
                if [ "$st" = "Stopped" ]; then break; fi
                sleep 5
            done
            aliyun --profile "$ALI_PROFILE" ecs DeleteInstance --RegionId "$ALI_REGION" --InstanceId "$IID" >/dev/null 2>&1 || true
            log "instance $IID released"
        else
            info "shutting down instance $IID ..."
            aliyun --profile "$ALI_PROFILE" ecs StopInstance --RegionId "$ALI_REGION" --InstanceId "$IID" >/dev/null 2>&1 || true
            log "instance $IID stopped (stopped instances still bill disk; use --release to delete)"
        fi
    fi
    exit $rc
}
trap cleanup EXIT INT TERM

header "Aliyun ConfirmedPaymentBenchmark :: $ALI_INSTANCE_TYPE @ $ALI_REGION, ${TX} tx, ${CLIENTS} clients, slot ${SLOT_MS}ms, txPerBlock ${TX_PER_BLOCK}"

ali() { aliyun --profile "$ALI_PROFILE" "$@"; }

ali() { # aliyun CLI with retry (transient DNS/token hiccups happen)
    local attempt out rc=1
    for attempt in 1 2 3; do
        if out=$(aliyun --profile "$ALI_PROFILE" "$@" 2>&1); then
            printf '%s\n' "$out"; return 0
        fi
        rc=$?
        echo -e "${YELLOW}[INFO]${NC} aliyun $1 failed (attempt ${attempt}/3): $(printf '%s' "$out" | grep -a 'ERROR:\|ErrorCode\|Message:' | head -1 | cut -c1-200)" >&2
        sleep 5
    done
    return $rc
}

# ── 1. Resolve image / vswitch / security group / key pair ───────────────────
if [ -z "$ALI_IMAGE_ID" ]; then
    info "resolving latest ubuntu_24_04_x64 system image in $ALI_REGION ..."
    ALI_IMAGE_ID=$(ali ecs DescribeImages --RegionId "$ALI_REGION" --ImageOwnerAlias system \
        --OSType linux --PageSize 100 \
        | jq -r '[.Images.Image[] | select(.ImageName|test("^ubuntu_24_04_x64_20G"))] | sort_by(.CreationTime) | last | .ImageId')
    [ -n "$ALI_IMAGE_ID" ] && [ "$ALI_IMAGE_ID" != "null" ] \
        || fail "no ubuntu_24_04_x64 image found — set ALI_IMAGE_ID explicitly"
fi
log "image: $ALI_IMAGE_ID"

if [ -n "$ALI_INSTANCE_ID" ]; then
    log "reusing existing instance $ALI_INSTANCE_ID (key file: $ALI_KEY_FILE)"
    IID="$ALI_INSTANCE_ID"
    PEM="$ALI_KEY_FILE"
    [ -f "$PEM" ] || fail "key file $PEM not found"
else
    if [ -z "$ALI_VSWITCH_ID" ]; then
        VPC=$(ali vpc DescribeVpcs --RegionId "$ALI_REGION" --PageSize 10 \
            | jq -r '[.Vpcs.Vpc[] | select(.IsDefault==true)][0].VpcId')
        [ -n "$VPC" ] && [ "$VPC" != "null" ] || fail "no default VPC in $ALI_REGION — set ALI_VSWITCH_ID"
        # pick a vswitch in a zone WITH stock for the instance type
        # (e.g. cn-hangzhou: g8i.16xlarge stocked in b/f/k, NOT the default-vpc h zone)
        ZONES=$(ali ecs DescribeAvailableResource --RegionId "$ALI_REGION" \
            --DestinationResource InstanceType --InstanceType "$ALI_INSTANCE_TYPE" \
            --InstanceChargeType PostPaid \
            | jq -r '.AvailableZones.AvailableZone[] | select(.StatusCategory=="WithStock") | .ZoneId' \
            | paste -sd' ' - || true)
        info "stocked zones for $ALI_INSTANCE_TYPE: ${ZONES:-<unknown, using first vswitch>}"
        ALI_VSWITCH_ID=$(ali vpc DescribeVSwitches --RegionId "$ALI_REGION" --VpcId "$VPC" --PageSize 50 \
            | jq -r '.VSwitches.VSwitch[] | "\(.VSwitchId) \(.ZoneId)"' \
            | while read -r vs zone; do
                  if [ -n "$ZONES" ] && printf ' %s ' "$ZONES" | grep -q " $zone "; then
                      echo "$vs"; break
                  fi
              done \
            | head -1)
        if [ -z "$ALI_VSWITCH_ID" ] && [ -n "$ZONES" ]; then
            # no existing vswitch in a stocked zone — create one (e.g. default VPC
            # only spans cn-hangzhou-h while g8i.16xlarge lives in b/f/k)
            ZONE=$(printf '%s' "$ZONES" | awk '{print $1}')
            USED=$(ali vpc DescribeVSwitches --RegionId "$ALI_REGION" --VpcId "$VPC" --PageSize 50 \
                | jq -r '.VSwitches.VSwitch[].CidrBlock')
            info "no vswitch in stocked zone — creating one in $ZONE"
            for c in $(seq 0 255); do
                CIDR="172.$((16 + c / 16)).$(((c % 16) * 16)).0/20"
                if printf '%s\n' "$USED" | grep -qxF "$CIDR"; then continue; fi
                NEW_VSW=$(ali vpc CreateVSwitch --RegionId "$ALI_REGION" --VpcId "$VPC" --ZoneId "$ZONE" \
                    --CidrBlock "$CIDR" --VSwitchName "$INSTANCE_NAME-net" | jq -r '.VSwitchId // empty') || NEW_VSW=""
                if [ -n "$NEW_VSW" ]; then break; fi
            done
            if [ -n "${NEW_VSW:-}" ]; then
                for i in $(seq 1 12); do
                    st=$(ali vpc DescribeVSwitches --RegionId "$ALI_REGION" --VSwitchId "$NEW_VSW" \
                        | jq -r '.VSwitches.VSwitch[0].Status')
                    if [ "$st" = "Available" ]; then break; fi
                    sleep 5
                done
                ALI_VSWITCH_ID="$NEW_VSW"
            fi
        fi
        [ -n "$ALI_VSWITCH_ID" ] \
            || ALI_VSWITCH_ID=$(ali vpc DescribeVSwitches --RegionId "$ALI_REGION" --VpcId "$VPC" --PageSize 10 \
                | jq -r '.VSwitches.VSwitch[0].VSwitchId')
        [ -n "$ALI_VSWITCH_ID" ] || fail "no vswitch in $VPC — set ALI_VSWITCH_ID"
        log "vswitch: $ALI_VSWITCH_ID (vpc $VPC)"
    fi

    if [ -z "$ALI_SECURITY_GROUP_ID" ]; then
        ALI_SECURITY_GROUP_ID=$(ali ecs DescribeSecurityGroups --RegionId "$ALI_REGION" \
            --SecurityGroupName "alitest-tps-sg" --PageSize 10 \
            | jq -r '.SecurityGroups.SecurityGroup[0].SecurityGroupId // empty')
        if [ -z "$ALI_SECURITY_GROUP_ID" ]; then
            VPCID=$(ali vpc DescribeVSwitches --RegionId "$ALI_REGION" --VSwitchId "$ALI_VSWITCH_ID" \
                | jq -r '.VSwitches.VSwitch[0].VpcId')
            ALI_SECURITY_GROUP_ID=$(ali ecs CreateSecurityGroup --RegionId "$ALI_REGION" \
                --VpcId "$VPCID" --SecurityGroupName "alitest-tps-sg" \
                --Description "alitest.sh ConfirmedPaymentBenchmark" | jq -r '.SecurityGroupId')
            info "created security group $ALI_SECURITY_GROUP_ID"
        fi
        # SSH must be reachable for provisioning (idempotent-ish; ignore duplicate)
        ali ecs AuthorizeSecurityGroup --RegionId "$ALI_REGION" --SecurityGroupId "$ALI_SECURITY_GROUP_ID" \
            --IpProtocol tcp --PortRange 22/22 --SourceCidrIp "$ALI_SSH_CIDR" >/dev/null 2>&1 || true
    fi
    log "security group: $ALI_SECURITY_GROUP_ID"

    if [ -n "$ALI_KEYPAIR" ]; then
        KP="$ALI_KEYPAIR"
        PEM="$STATE_DIR/alitest-$KP.pem"
        [ -f "$PEM" ] || fail "keypair $KP set but private key $PEM missing"
    else
        KP="alitest-tps-$STAMP"
        PEM="$STATE_DIR/$KP.pem"
        info "creating key pair $KP ..."
        ali ecs CreateKeyPair --RegionId "$ALI_REGION" --KeyPairName "$KP" \
            | jq -r '.PrivateKeyBody' > "$PEM"
        if [ "$(wc -c < "$PEM")" -lt 100 ] || ! grep -q "BEGIN.*PRIVATE KEY" "$PEM"; then
            fail "key pair $KP: invalid private key written to $PEM"
        fi
        chmod 600 "$PEM"
    fi
    log "key pair: $KP"

    # ── 2. Create the instance (postpaid, essd 200G, public IP) ──────────────
    info "creating $ALI_INSTANCE_TYPE ..."
    IID=$(ali ecs RunInstances \
        --RegionId "$ALI_REGION" \
        --ImageId "$ALI_IMAGE_ID" \
        --InstanceType "$ALI_INSTANCE_TYPE" \
        --SecurityGroupId "$ALI_SECURITY_GROUP_ID" \
        --VSwitchId "$ALI_VSWITCH_ID" \
        --InstanceName "$INSTANCE_NAME" \
        --InstanceChargeType PostPaid \
        --InternetChargeType PayByTraffic \
        --InternetMaxBandwidthOut "$ALI_BANDWIDTH" \
        --SystemDisk.Category cloud_essd \
        --SystemDisk.Size 200 \
        --KeyPairName "$KP" \
        --Amount 1 \
        --Description "ConfirmedPaymentBenchmark max-TPS run (alitest.sh)" \
        | jq -r '.InstanceIdSets.InstanceIdSet[0]')
    [ -n "$IID" ] || fail "RunInstances returned no instance id"
    log "instance created: $IID"
fi

echo "$IID" > "$STATE_DIR/alitest.iid"

# ── 3. Wait for Running + public IP + SSH ────────────────────────────────────
inst_status() { ali ecs DescribeInstances --RegionId "$ALI_REGION" \
    --InstanceIds "[\"$IID\"]" | jq -r '.Instances.Instance[0].Status'; }

if [ "$(inst_status)" != "Running" ]; then
    if [ -n "$ALI_INSTANCE_ID" ]; then
        info "starting existing instance $IID ..."
        ali ecs StartInstance --RegionId "$ALI_REGION" --InstanceId "$IID" >/dev/null \
            || fail "cannot start $IID"
    fi
    info "waiting for instance to reach Running ..."
    for i in $(seq 1 45); do
        if [ "$(inst_status)" = "Running" ]; then break; fi
        sleep 10
    done
    [ "$(inst_status)" = "Running" ] || fail "instance not Running after 7.5 min"
fi
log "instance $IID is Running"

info "waiting for public IP ..."
IP=""
for i in $(seq 1 18); do
    IP=$(ali ecs DescribeInstances --RegionId "$ALI_REGION" --InstanceIds "[\"$IID\"]" \
        | jq -r '.Instances.Instance[0] | (.PublicIpAddress.IpAddress[0] // .EipAddress.IpAddress // empty)')
    if [ -n "$IP" ]; then break; fi
    sleep 10
done
[ -n "$IP" ] || fail "no public IP assigned"
echo "$IP" > "$STATE_DIR/alitest.ip"
log "public IP: $IP"

SSH_OPTS=(-i "$PEM" -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=8 -o LogLevel=ERROR)
info "waiting for SSH ..."
for i in $(seq 1 30); do
    if ssh "${SSH_OPTS[@]}" "root@$IP" true 2>/dev/null; then break; fi
    sleep 10
done
ssh "${SSH_OPTS[@]}" "root@$IP" true 2>/dev/null || fail "SSH not reachable on $IP"
log "SSH ready"

# ── 4. Remote provision: JDK 25 + PostgreSQL + maven + sysctls ───────────────
header "Provisioning $IP"
ssh "${SSH_OPTS[@]}" "root@$IP" "bash -s" -- "$HEAP" <<'REMOTE_BOOT' || fail "provisioning failed"
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq postgresql maven rsync jq curl unzip >/dev/null
ARCH=$(uname -m); [ "$ARCH" = "x86_64" ] && A=x64 || A=aarch64
if [ ! -x /opt/jdk25/bin/java ]; then
    # fast-in-CN mirror first (TUNA), Adoptium API fallback
    M=$(curl -fsSL --max-time 30 "https://mirrors.tuna.tsinghua.edu.cn/Adoptium/25/jdk/$A/linux/" 2>/dev/null \
        | grep -oE "OpenJDK25U-jdk_${A}_linux_hotspot_[0-9._-]+\\.tar\\.gz" | sort -V | tail -1 || true)
    for u in ${M:+https://mirrors.tuna.tsinghua.edu.cn/Adoptium/25/jdk/$A/linux/$M} \
             "https://api.adoptium.net/v3/binary/latest/25/ga/linux/$A/jdk/hotspot/normal/eclipse"; do
        if curl -fsSL --max-time 1200 "$u" -o /tmp/jdk25.tgz 2>/dev/null && [ "$(wc -c < /tmp/jdk25.tgz)" -gt 50000000 ]; then
            break
        fi
    done
    tar -tzf /tmp/jdk25.tgz >/dev/null 2>&1 || { echo JDK_DOWNLOAD_FAILED; exit 1; }
    mkdir -p /opt/jdk25
    tar -xzf /tmp/jdk25.tgz -C /opt/jdk25 --strip-components=1
fi
/opt/jdk25/bin/java -version 2>&1 | head -1
# benchmark host tuning (docs/performance.md: host CPU bound, postgres never the constraint)
cat >/etc/sysctl.d/99-bigtangle.conf <<'SYS'
vm.overcommit_memory=1
vm.swappiness=1
net.core.somaxconn=65535
net.ipv4.tcp_max_syn_backlog=8192
SYS
sysctl --system >/dev/null
systemctl start postgresql
su postgres -c "psql -tAc \"SELECT 1 FROM pg_roles WHERE rolname='root'\"" | grep -q 1 \
    || su postgres -c "psql -c \"CREATE USER root WITH SUPERUSER PASSWORD 'test1234';\""
# fresh chain per run — never compare numbers on a shared DB (performance.md)
su postgres -c "dropdb --if-exists layer0"
su postgres -c "createdb -O root layer0"
echo BOOTSTRAP_DONE
REMOTE_BOOT
log "JDK 25 + PostgreSQL + maven installed"

# ── 5. Sync the workspace (code only — no logs, no build output) ─────────────
info "rsyncing $ROOT -> root@$IP:/opt/bigtangle ..."
rsync -az --info=stats1 -e "ssh ${SSH_OPTS[*]}" \
    --exclude .git --exclude target --exclude logs --exclude '*.log' \
    --exclude node-*-server.cid --exclude .l1genesispub --exclude .l1validatorpub \
    "$ROOT/" "root@$IP:/opt/bigtangle/"
log "workspace synced"

# ── 6. Build + run ConfirmedPaymentBenchmark ─────────────────────────────────
header "Running ConfirmedPaymentBenchmark on $IP (${TX} tx)"
ssh "${SSH_OPTS[@]}" "root@$IP" "bash -s" -- \
    "$TX" "$CLIENTS" "$BATCH" "$MIN_TX" "$MAX_AGE" "$SLOT_MS" "$TX_PER_BLOCK" "$HEAP" "$CONFIRM_TIMEOUT" "$MEMPOOL" \
    <<'REMOTE_BENCH' > "$BENCH_LOG" || BENCH_FAILED=true
set -euo pipefail
TX=$1; CLIENTS=$2; BATCH=$3; MIN_TX=$4; MAX_AGE=$5; SLOT_MS=$6; TPB=$7; HEAP=$8; CTO=$9; MEMPOOL=${10}
export JAVA_HOME=/opt/jdk25
export PATH="$JAVA_HOME/bin:$PATH"
export MAVEN_OPTS="-Xmx4g"
cd /opt/bigtangle
java -version 2>&1 | head -1

# same workaround as benchmarklocal.sh: RemoteOrderIT has an undefined symbol
BROKEN=layer0-server/src/test/java/net/bigtangle/server/remote/RemoteOrderIT.java
if [ -f "$BROKEN" ]; then mv "$BROKEN" /tmp/RemoteOrderIT.java.bak; fi
restore() { [ -f /tmp/RemoteOrderIT.java.bak ] && mv /tmp/RemoteOrderIT.java.bak "$BROKEN" || true; }
trap restore EXIT

echo "== build reactor deps =="
mvn -q install -DskipTests -pl bigtangle-core,bigtangle-servercore,bigtangle-bridge -am

echo "== benchmark: ConfirmedPaymentBenchmark =="
mvn test -pl layer0-server \
    -Dtest=ConfirmedPaymentBenchmark \
    -Dbench.tx="$TX" -Dbench.clients="$CLIENTS" -Dbench.batch="$BATCH" \
    -Dbench.confirmTimeoutSec="$CTO" \
    -Dbatch.minTx="$MIN_TX" -Dbatch.maxBatchAgeMs="$MAX_AGE" -Dbatch.txPerBlock="$TPB" \
    -Dserver.mempoolMaxTx="$MEMPOOL" \
    -Dpos.slotIntervalMs="$SLOT_MS" -Ddb.dbName=layer0 -Ddb.port=5432 \
    "-DargLine=-Xmx${HEAP} --add-exports java.base/sun.nio.ch=ALL-UNNAMED --add-exports java.base/java.lang=ALL-UNNAMED" \
    -DforkedProcessTimeoutInSeconds=0 -DfailIfNoTests=false
REMOTE_BENCH

header "Results (full log: $BENCH_LOG)"
strip() { sed 's/\x1b\[[0-9;]*m//g' "$1"; }
total=$(strip "$BENCH_LOG" | grep -oP 'Total tx:\s+\K\d+ \(' | tail -1 | tr -d '(' || true)
submitted=$(strip "$BENCH_LOG" | grep -oP 'submitted \K\d+' | tail -1 || true)
confirmed=$(strip "$BENCH_LOG" | grep -oP '\(final \K\d+' | tail -1 || true)
submit_tps=$(strip "$BENCH_LOG" | grep -oP 'Submit TPS:\s+\K[\d.]+' | tail -1 || true)
confirm_tps=$(strip "$BENCH_LOG" | grep -oP 'CONFIRMED TPS:\s+\K[\d.]+' | tail -1 || true)

printf "  %-18s %s\n" "Instance:"       "$ALI_INSTANCE_TYPE @ $ALI_REGION ($IID)"
printf "  %-18s %s\n" "Benchmark:"     "ConfirmedPaymentBenchmark (single server)"
printf "  %-18s %s / %s\n"   "Confirmed:"     "${confirmed:-?}" "${total:-$TX}"
printf "  %-18s %s tx/s\n"   "Submit TPS:"    "${submit_tps:-N/A}"
printf "  %-18s %s tx/s\n"   "CONFIRMED TPS:" "${confirm_tps:-N/A}"
printf "  %-18s %s\n" "Result file:" "$BENCH_LOG"

if [ "${BENCH_FAILED:-false}" = true ]; then
    fail "benchmark failed — see $BENCH_LOG"
fi
if [ -z "${confirm_tps:-}" ] || [ "${confirmed:-0}" -eq 0 ]; then
    fail "no confirmed transactions — see $BENCH_LOG"
fi
log "max TPS: ${confirm_tps} tx/s confirmed (${submit_tps:-?} tx/s submit) — instance $IID will now be shut down"
