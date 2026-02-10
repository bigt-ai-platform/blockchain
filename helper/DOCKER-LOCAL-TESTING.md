# Local Docker Testing Setup

This guide explains how to run BigTangle locally with Docker, with the MCMC service running separately to update MCMC and tips.

## Architecture

The local setup consists of 4 services:

1. **test-bigtangle-postgres**: PostgreSQL database for storing blockchain data
2. **minio**: Object storage for blocks and large data
3. **test-bigtangle-server**: Main BigTangle server (handles transactions, blocks, API)
4. **test-bigtangle-mcmc**: MCMC service (updates MCMC calculations and tips queue)

## Key Changes

- **Server**: Runs without MCMC (`SERVICE_MCMC=false`)
- **MCMC Service**: Runs independently, updating MCMC and tips every 1 second (`SERVICE_MCMC_RATE=1000`)
- Both services share the same database and MinIO storage

## Prerequisites

- Docker and Docker Compose installed
- At least 8GB of RAM available
- Ports 5432, 8089, 8090, 9000, 9001 available

## Usage

### Start All Services

```bash
cd /home/jcui/git/server/helper
docker-compose -f docker-compose-local.yml up -d
```

### View Logs

```bash
# All services
docker-compose -f docker-compose-local.yml logs -f

# Specific service
docker-compose -f docker-compose-local.yml logs -f test-bigtangle-server
docker-compose -f docker-compose-local.yml logs -f test-bigtangle-mcmc
```

### Check Service Status

```bash
docker-compose -f docker-compose-local.yml ps
```

### Stop All Services

```bash
docker-compose -f docker-compose-local.yml down
```

### Clean Up (Remove All Data)

```bash
docker-compose -f docker-compose-local.yml down -v
```

## Service Endpoints

- **Server API**: http://localhost:8089
- **MCMC API**: http://localhost:8090
- **MinIO Console**: http://localhost:9001 (admin/minioadminpassword)
- **MinIO API**: http://localhost:9000
- **PostgreSQL**: localhost:5432 (root/test1234)

## Configuration

### MCMC Update Rate

The MCMC service updates every 1 second by default. To change this, modify the `SERVICE_MCMC_RATE` environment variable in `docker-compose-local.yml`:

```yaml
SERVICE_MCMC_RATE: 1000  # milliseconds (1000 = 1 second)
```

Common values:
- 500ms: Very frequent updates (high CPU usage)
- 1000ms: Default, good for local testing
- 5000ms: Less frequent updates (lower CPU usage)

### Memory Configuration

Default memory allocation:
- Server: 4GB (`-Xmx4096m`)
- MCMC: 2GB (`-Xmx2048m`)

Adjust the `JAVA_OPTS` environment variable if needed.

## Rebuilding After Code Changes

```bash
# Rebuild and restart specific service
docker-compose -f docker-compose-local.yml build test-bigtangle-server
docker-compose -f docker-compose-local.yml up -d test-bigtangle-server

# Or rebuild all
docker-compose -f docker-compose-local.yml build
docker-compose -f docker-compose-local.yml up -d
```

## Troubleshooting

### MCMC Service Not Updating Tips

Check the MCMC service logs:
```bash
docker-compose -f docker-compose-local.yml logs -f test-bigtangle-mcmc | grep -i "mcmc\|tips"
```

### Database Connection Issues

Verify PostgreSQL is healthy:
```bash
docker-compose -f docker-compose-local.yml ps test-bigtangle-postgres
```

### MinIO Not Accessible

Check MinIO health:
```bash
curl http://localhost:9000/minio/health/live
```

### Port Already in Use

If ports are already in use, modify the port mappings in `docker-compose-local.yml`:

```yaml
ports:
  - "NEW_PORT:8088"  # Change NEW_PORT to an available port
```

## Development Workflow

1. Make code changes
2. Rebuild the affected service:
   ```bash
   docker-compose -f docker-compose-local.yml build test-bigtangle-server
   ```
3. Restart the service:
   ```bash
   docker-compose -f docker-compose-local.yml up -d test-bigtangle-server
   ```
4. View logs to verify changes:
   ```bash
   docker-compose -f docker-compose-local.yml logs -f test-bigtangle-server
   ```

## Testing MCMC Updates

To verify MCMC is updating tips correctly:

```bash
# Watch MCMC logs for tip updates
docker-compose -f docker-compose-local.yml logs -f test-bigtangle-mcmc | grep -i "tip"

# Query the database to see tips queue
docker exec -it test-bigtangle-postgres psql -U root -d info -c "SELECT * FROM tips_queue ORDER BY time DESC LIMIT 5;"
```

## Notes

- First startup takes longer as Docker builds images and initializes the database
- Data persists in Docker volumes between restarts
- Use `docker-compose down -v` to completely reset the environment
- The MCMC service needs existing blocks in the database to generate tips
