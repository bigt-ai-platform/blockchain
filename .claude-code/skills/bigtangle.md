# BigTangle Development Skill

## Description
Development workflow automation for BigTangle blockchain server - handles building, testing, running, and common development tasks for this distributed blockchain platform with order matching capabilities.

## Usage
Use this skill when working on BigTangle server development tasks:
- `/bigtangle build` - Build the project
- `/bigtangle test` - Run tests
- `/bigtangle run` - Start the server
- `/bigtangle order-test` - Run order matching tests
- `/bigtangle clean` - Clean build artifacts
- `/bigtangle docker` - Build and run Docker container
- `/bigtangle db` - Set up database
- `/bigtangle status` - Check git status and project state

## Instructions

You are helping with the BigTangle blockchain server project. This is a distributed blockchain platform with order matching capabilities built with Java 17, Spring Boot, and PostgreSQL.

### Project Structure
- **bigtangle-core**: Core blockchain implementation (Block, Transaction, UTXO, Order matching)
- **bigtangle-server**: Main server with REST API and services
- **bigtangle-seeds**: Seed node implementation
- **bigtangle-subtangle**: Subtangle implementation
- **helper**: Deployment and testing scripts

### Key Technologies
- Java 17
- Spring Boot 3.1.0
- Maven (multi-module)
- PostgreSQL (primary) / MySQL (fallback)
- Kafka for messaging
- Docker for deployment

### Common Commands

#### Build Commands
```bash
# Full build with tests
mvn clean install

# Build without tests (faster)
mvn clean install -DskipTests=true

# Build specific module
cd bigtangle-core && mvn clean install
cd bigtangle-server && mvn clean install
```

#### Test Commands
```bash
# Run all tests
mvn test

# Run specific test class
mvn test -Dtest=OrderMatchTest

# Run specific test method
mvn test -Dtest=OrderMatchTest#testOrderMatch
```

#### Run Commands
```bash
# Run server with proper JVM settings
java -Xmx5028m --add-exports java.base/sun.nio.ch=ALL-UNNAMED net.bigtangle.server.ServerStart

# Run with specific profile
java -Xmx5028m --add-exports java.base/sun.nio.ch=ALL-UNNAMED -Dspring.profiles.active=prod net.bigtangle.server.ServerStart
```

#### Docker Commands
```bash
# Build Docker image
docker build -t bigtangle -f helper/bigtangle/Dockerfile .

# Run with test script
sh helper/testdocker.sh

# Run container manually
docker run -p 8088:8088 bigtangle
```

#### Database Setup
```bash
# Set up database (production)
cd helper/divers
sh db.sh

# Database is configured in:
# src/main/resources/application.yml
```

### Key Files to Know
- **Configuration**: `bigtangle-server/src/main/resources/application.yml`
- **Main Entry**: `bigtangle-server/src/main/java/net/bigtangle/server/ServerStart.java`
- **REST API**: `bigtangle-server/src/main/java/net/bigtangle/server/DispatcherController.java`
- **Order Matching**: `bigtangle-core/src/main/java/net/bigtangle/core/ordermatch/`
- **Tests**: `bigtangle-server/src/test/java/net/bigtangle/server/test/`

### Architecture Patterns
- Layered architecture: Controller → Service → Store → Database
- Service base hierarchy: ServiceBase → ServiceBaseOrder → ServiceBaseConfirmation
- Multiple store implementations: PostgreSQL, MySQL
- Spring dependency injection throughout

### Development Workflow

When the user runs specific commands:

**For `/bigtangle build`:**
1. Check current git status
2. Run `mvn clean install -DskipTests=true`
3. Report build success/failure
4. Show any compilation errors

**For `/bigtangle test`:**
1. Check if tests are available
2. Run `mvn test`
3. Report test results
4. Show failures if any

**For `/bigtangle run`:**
1. Check if build is up to date
2. Run server with: `java -Xmx5028m --add-exports java.base/sun.nio.ch=ALL-UNNAMED net.bigtangle.server.ServerStart`
3. Monitor startup logs
4. Report when server is ready (default port 8088)

**For `/bigtangle order-test`:**
1. Run order matching tests specifically
2. Execute: `mvn test -Dtest=OrderMatchTest`
3. Report results

**For `/bigtangle clean`:**
1. Run `mvn clean`
2. Remove target directories
3. Report cleaned artifacts

**For `/bigtangle docker`:**
1. Build Docker image: `docker build -t bigtangle -f helper/bigtangle/Dockerfile .`
2. Run test script: `sh helper/testdocker.sh`
3. Report container status

**For `/bigtangle db`:**
1. Check database configuration in application.yml
2. Run database setup script if needed: `sh helper/divers/db.sh`
3. Verify connection

**For `/bigtangle status`:**
1. Run `git status`
2. Check recent commits
3. Show modified files
4. Check if build is needed

### Important Notes
- Always use Maven wrapper (`mvn`) if available
- The project requires Java 17 minimum
- Default server port is 8088
- Main branch is `ordermatch`, development on `develop`
- Order matching is a core feature - handle with care
- Database must be running for integration tests

### Code Conventions
- Follow existing Java code style in the project
- Services extend from ServiceBase hierarchy
- Store implementations follow BlockStoreInterface
- Use Spring annotations for dependency injection
- Order matching code is in bigtangle-core/ordermatch package

### When Modifying Code
1. Always read existing code first
2. Follow the established patterns
3. Run tests after changes
4. Update tests if behavior changes
5. Consider impact on order matching system
6. Check database schema if modifying entities

### Commit Message Format
Use the project's format:
```
<Short description>

<Detailed description if needed>

Generated with [Claude Code](https://claude.ai/code)
via [Happy](https://happy.engineering)

Co-Authored-By: Claude <noreply@anthropic.com>
Co-Authored-By: Happy <yesreply@happy.engineering>
```
