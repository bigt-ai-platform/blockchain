#!/usr/bin/env bash
# Generate a deployment-specific PKCS12 TLS keystore for the layer0/L1 servers.
#
# The keystore referenced by the default application.yml
# (server.ssl.key-store) is a DEVELOPMENT artifact and must never be used on
# a public node. Run this once per deployment (or per node, if you prefer
# node-specific certificates):
#
#   bash helper/prod/generate_keystore.sh node0.bigtangle.org
#
# Then start the server with:
#   SSL=true KEYSTORE=/path/to/keystore.pkcs12 KEYSTOREPW=<generated> KEYSTORETYPE=PKCS12
set -euo pipefail

HOSTNAME="${1:-$(hostname -f)}"
OUT="${2:-./ca.pkcs12}"

if [ -f "${OUT}" ]; then
    echo "ERROR: ${OUT} already exists — refusing to overwrite" >&2
    exit 1
fi

# Never reuse a fixed password: generate a fresh one and print it exactly once.
PASSWORD="$(openssl rand -hex 24)"

keytool -genkeypair \
    -alias bigtangle \
    -keyalg RSA -keysize 2048 -sigalg SHA256withRSA \
    -validity 825 \
    -dname "CN=${HOSTNAME}, O=BigTangle" \
    -storetype PKCS12 -keystore "${OUT}" \
    -storepass "${PASSWORD}" -keypass "${PASSWORD}"

chmod 600 "${OUT}"

echo "Keystore written: ${OUT}"
echo "Start the server with:"
echo "  SSL=true KEYSTORE=${OUT} KEYSTOREPW=${PASSWORD} KEYSTORETYPE=PKCS12"
echo "Store the password in a secret manager / gitignored env file. Do not log it."
