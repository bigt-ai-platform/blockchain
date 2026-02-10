#!/bin/bash
# Test script to verify MCMC tips generation in Docker environment

echo "=== BigTangle Docker Local Test ==="
echo ""

# Check database connection
echo "1. Checking database connection..."
docker exec test-bigtangle-postgres psql -U root -d info -c "SELECT 1;" > /dev/null 2>&1
if [ $? -eq 0 ]; then
    echo "   ✓ Database connected"
else
    echo "   ✗ Database connection failed"
    exit 1
fi

# Check initial block count
echo ""
echo "2. Checking initial block count..."
BLOCK_COUNT=$(docker exec test-bigtangle-postgres psql -U root -d info -t -c "SELECT COUNT(*) FROM blocks;" | tr -d ' ')
echo "   Current blocks in database: $BLOCK_COUNT"

# Check tips queue
echo ""
echo "3. Checking tips queue..."
TIPS_COUNT=$(docker exec test-bigtangle-postgres psql -U root -d info -t -c "SELECT COUNT(*) FROM tipsqueue;" | tr -d ' ')
echo "   Current tips in queue: $TIPS_COUNT"

if [ "$TIPS_COUNT" -gt 0 ]; then
    echo ""
    echo "   Latest tips:"
    docker exec test-bigtangle-postgres psql -U root -d info -c "SELECT encode(hash, 'hex') as tip_hash, height, inserttime FROM tipsqueue ORDER BY inserttime DESC LIMIT 5;"
fi

# Check server status
echo ""
echo "4. Checking server API..."
SERVER_STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8089/actuator/health)
if [ "$SERVER_STATUS" = "200" ]; then
    echo "   ✓ Server API is healthy (HTTP $SERVER_STATUS)"
else
    echo "   ✗ Server API returned HTTP $SERVER_STATUS"
fi

# Check MCMC status
echo ""
echo "5. Checking MCMC API..."
MCMC_STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8090/actuator/health)
if [ "$MCMC_STATUS" = "200" ]; then
    echo "   ✓ MCMC API is healthy (HTTP $MCMC_STATUS)"
else
    echo "   ✗ MCMC API returned HTTP $MCMC_STATUS"
fi

# Try to get a tip from the server
echo ""
echo "6. Testing getTip endpoint..."
TIP_RESPONSE=$(curl -s -X POST http://localhost:8089/getTip -H "Content-Type: application/json" -d '{}')
TIP_RESULT=$?

if [ $TIP_RESULT -eq 0 ] && [ ! -z "$TIP_RESPONSE" ]; then
    # Check if response is binary (successful) or JSON error
    if echo "$TIP_RESPONSE" | grep -q "error\|message"; then
        echo "   ✗ getTip returned error: $TIP_RESPONSE"
    else
        echo "   ✓ getTip returned a tip block (binary data received)"
    fi
else
    echo "   ✗ getTip request failed"
fi

echo ""
echo "=== Summary ==="
echo "Blocks in database: $BLOCK_COUNT"
echo "Tips in queue: $TIPS_COUNT"
echo ""

if [ "$BLOCK_COUNT" -eq 0 ]; then
    echo "⚠ No blocks in database. The system needs blocks to generate tips."
    echo "   To add blocks, you can:"
    echo "   1. Run integration tests that create blocks"
    echo "   2. Use the API to create transactions and blocks"
    echo "   3. Initialize with genesis blocks programmatically"
else
    echo "✓ System has blocks. MCMC service should be generating tips."
fi
