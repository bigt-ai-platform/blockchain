#!/usr/bin/env bash
# Generate per-validator credentials (POS_VALIDATOR_KEY / VALIDATOR_PUBKEY /
# PUBKEY_HASH / ADDRESS) into node-<i>/validator.env.
#
# Usage:  N_VALIDATORS=4 ./generate_keys.sh
#
# Uses net.bigtangle.tools.ValidatorKeyTool from the packaged layer0-server Docker image.
set -euo pipefail

cd "$(dirname "$0")"

# shellcheck source=common.env
source ./common.env

N_VALIDATORS="${N_VALIDATORS:-4}"
TOOL_IMAGE="${TOOL_IMAGE:-${SERVER_IMAGE}:${IMAGE_TAG}}"

if ! docker image inspect "${TOOL_IMAGE}" >/dev/null 2>&1; then
    echo "Docker image not found: ${TOOL_IMAGE}  (build with 'helper/deploy.sh' or set TOOL_IMAGE=)" >&2
    exit 1
fi

for i in $(seq 0 $((N_VALIDATORS - 1))); do
    node_dir="node-${i}"
    mkdir -p "${node_dir}"
    out="$(docker run --rm --network none --entrypoint java "${TOOL_IMAGE}" -cp /app/app.jar net.bigtangle.tools.ValidatorKeyTool generate)"

    key="$(echo "$out" | grep '^POS_VALIDATOR_KEY='   | cut -d= -f2-)"
    pub="$(echo "$out" | grep '^VALIDATOR_PUBKEY='    | cut -d= -f2-)"
    hash="$(echo "$out" | grep '^PUBKEY_HASH='        | cut -d= -f2-)"
    addr="$(echo "$out" | grep '^ADDRESS='           | cut -d= -f2-)"

    if [ -z "${key}" ] || [ -z "${pub}" ]; then
        echo "ValidatorKeyTool produced no key for node ${i}: ${out}" >&2
        exit 1
    fi

    cat > "${node_dir}/validator.env" <<EOF
# Node ${i} validator credentials — KEEP SECRET (gitignored).
NODE_INDEX=${i}
NODE_HOST=REPLACE_WITH_NODE_${i}_HOST
POS_VALIDATOR_KEY=${key}
VALIDATOR_PUBKEY=${pub}
PUBKEY_HASH=${hash}
ADDRESS=${addr}
EOF
    chmod 600 "${node_dir}/validator.env"
    echo "wrote ${node_dir}/validator.env  (address=${addr})"
done

echo "Edit each node-<i>/validator.env and set NODE_HOST to that node's reachable IP/hostname."
