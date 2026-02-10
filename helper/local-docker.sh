#!/bin/bash
# Local Docker Testing Helper Script

COMPOSE_FILE="docker-compose-local.yml"

case "$1" in
  start)
    echo "Starting local BigTangle environment..."
    docker compose -f "$COMPOSE_FILE" up -d
    echo ""
    echo "Services started! Endpoints:"
    echo "  - Server API: http://localhost:8089"
    echo "  - MCMC API: http://localhost:8090"
    echo "  - MinIO Console: http://localhost:9001"
    echo ""
    echo "View logs: ./local-docker.sh logs"
    ;;

  stop)
    echo "Stopping local BigTangle environment..."
    docker compose -f "$COMPOSE_FILE" down
    ;;

  restart)
    echo "Restarting local BigTangle environment..."
    docker compose -f "$COMPOSE_FILE" restart
    ;;

  logs)
    SERVICE="${2:-}"
    if [ -z "$SERVICE" ]; then
      docker compose -f "$COMPOSE_FILE" logs -f
    else
      docker compose -f "$COMPOSE_FILE" logs -f "$SERVICE"
    fi
    ;;

  ps|status)
    docker compose -f "$COMPOSE_FILE" ps
    ;;

  build)
    SERVICE="${2:-}"
    echo "Building images..."
    if [ -z "$SERVICE" ]; then
      docker compose -f "$COMPOSE_FILE" build
    else
      docker compose -f "$COMPOSE_FILE" build "$SERVICE"
    fi
    ;;

  rebuild)
    SERVICE="${2:-test-bigtangle-server}"
    echo "Rebuilding $SERVICE..."
    docker compose -f "$COMPOSE_FILE" build "$SERVICE"
    docker compose -f "$COMPOSE_FILE" up -d "$SERVICE"
    echo "Service $SERVICE rebuilt and restarted"
    ;;

  clean)
    echo "Stopping and removing all containers and volumes..."
    read -p "This will delete all data! Continue? (y/N) " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
      docker compose -f "$COMPOSE_FILE" down -v
      echo "Environment cleaned!"
    fi
    ;;

  exec)
    SERVICE="${2:-test-bigtangle-server}"
    shift 2
    docker compose -f "$COMPOSE_FILE" exec "$SERVICE" "$@"
    ;;

  db)
    echo "Connecting to PostgreSQL..."
    docker exec -it test-bigtangle-postgres psql -U root -d info
    ;;

  tips)
    echo "Checking tips queue..."
    docker exec -it test-bigtangle-postgres psql -U root -d info -c "SELECT encode(hash, 'hex') as hash, height, inserttime FROM tipsqueue ORDER BY inserttime DESC LIMIT 10;"
    ;;

  test)
    echo "Running Docker local test..."
    bash "$(dirname "$0")/test-tips-generation.sh"
    ;;

  *)
    echo "Local Docker Testing Helper"
    echo ""
    echo "Usage: $0 <command> [options]"
    echo ""
    echo "Commands:"
    echo "  start          Start all services"
    echo "  stop           Stop all services"
    echo "  restart        Restart all services"
    echo "  logs [service] View logs (optionally for specific service)"
    echo "  ps|status      Show service status"
    echo "  build [service] Build images"
    echo "  rebuild [service] Rebuild and restart service (default: server)"
    echo "  clean          Stop and remove all data (DESTRUCTIVE)"
    echo "  exec <service> <cmd> Execute command in container"
    echo "  db             Connect to PostgreSQL"
    echo "  tips           Show recent tips queue entries"
    echo "  test           Run comprehensive system test"
    echo ""
    echo "Examples:"
    echo "  $0 start"
    echo "  $0 logs test-bigtangle-mcmc"
    echo "  $0 rebuild test-bigtangle-server"
    echo "  $0 tips"
    echo "  $0 test"
    echo ""
    exit 1
    ;;
esac
