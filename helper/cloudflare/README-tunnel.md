# Cloudflare Tunnel Setup

This document describes how to set up Cloudflare Tunnel to expose your BigTangle server via `test.bigtangle.org`.

## Prerequisites

- Cloudflare account with domain `bigtangle.org` managed
- Docker and Docker Compose installed
- `cloudflared` CLI installed locally

## Architecture

```
Internet → Cloudflare → Cloudflare Tunnel → Docker Container (BigTangle)
```

## Files

| File | Description |
|------|-------------|
| `docker-compose-tunnel.yml` | Main compose file with BigTangle server + cloudflared |
| `cloudflared-config.yml` | Tunnel ingress routing configuration |
| `credentials.json` | Tunnel credentials (you must provide) |

## Quick Start

### 1. Install cloudflared

```bash
# macOS
brew install cloudflared

# Linux
curl -L https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64 -o ~/.local/bin/cloudflared
chmod +x ~/.local/bin/cloudflared
```

### 2. Create Tunnel

```bash
# Login to Cloudflare (opens browser)
~/.local/bin/cloudflared tunnel login

# Create tunnel
~/.local/bin/cloudflared tunnel create test-bigtangle
```

Copy the credentials file:
```bash
cp ~/.cloudflared/<tunnel-id>.json ./credentials.json
```

### 3. Add DNS Records

```bash
~/.local/bin/cloudflared tunnel route dns test-bigtangle test.bigtangle.org
```

### 4. Start Services

```bash
docker compose -f docker-compose-tunnel.yml up -d
```

### 5. Verify

```bash
# Check containers
docker ps

# Test tunnel
curl http://test.bigtangle.org/getChainHeight
```

## Running Tests

Set the test environment variable:

```bash
cd /home/jcui/git/bigtangle-ts
INCLUDE_INTEGRATION_TESTS=1 TEST_CONTEXT_ROOT=http://test.bigtangle.org/ npx vitest run test/testintegration/RemoteFromAddressTests.test.ts
```

## Troubleshooting

### Check cloudflared logs
```bash
docker logs cloudflared
```

### Check BigTangle server logs
```bash
docker logs test-bigtangle
```

### Restart services
```bash
docker compose -f docker-compose-tunnel.yml restart
```

### Common Issues

1. **502 Bad Gateway**: Server not running or not listening on correct port
2. **Connection refused**: Check SERVER_PORT matches cloudflared ingress config
3. **Permission denied on credentials.json**: Run `chmod 644 credentials.json`

## Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `REQUESTER` | Server requester URL for P2P sync | `http://test-bigtangle:18089` |
| `SERVER_PORT` | Internal server port | `18089` |
| `DB_HOSTNAME` | PostgreSQL container | `test-bigtangle-postgres` |

## Tunnel Configuration

The tunnel routes:

- `test.bigtangle.org` → BigTangle server (port 18089)
- `test-mcmc.bigtangle.org` → BigTangle MCMC (port 18090)
- `test-minio.bigtangle.org` → MinIO console (port 9001)
- `test-minio-api.bigtangle.org` → MinIO API (port 9000)

Edit `cloudflared-config.yml` to modify routing.

## With MCMC Support

Use `docker-compose-tunnel-mcmc.yml` for a setup with MCMC (Mining & Consensus) service:

```bash
docker compose -f docker-compose-tunnel-mcmc.yml up -d
```

This includes:
- BigTangle server (SERVICE_MCMC=false)
- BigTangle MCMC (SERVICE_MCMC=true, connects to server)

## Stopping

```bash
docker compose -f docker-compose-tunnel.yml down
```

## Rebuilding Server

If you modify the BigTangle server code:

```bash
docker compose -f docker-compose-tunnel.yml build test-bigtangle
docker compose -f docker-compose-tunnel.yml up -d test-bigtangle
```
