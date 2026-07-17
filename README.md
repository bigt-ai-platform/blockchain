# BigTangle Blockchain Server

BigTangle is a distributed blockchain platform implementation featuring:

## Key Components
- **Core Blockchain**: Implements the main blockchain protocol with proof-of-work consensus
- **Server Nodes**: Multiple node types including core servers, seed nodes, and subtangle servers
- **Wallet**: Secure cryptocurrency wallet implementation


## Technical Stack
- **Languages**: Java (main), C++ (proof-of-work),
- **Build System**: Maven
- **Runtime**: Java 25
- **Database**: PostgreSQL
- **Messaging**: Kafka integration

## Deployment Options
1. **Docker Development**:
```bash
git clone https://github.com/bigtangle/server.git
cd server/helper/bigtangle
cd server
docker build -t  bigtangle  -f helper/bigtangle/Dockerfile . 
sh helper/testdocker.sh
```

2. **Production Deployment**:
```bash
git clone https://github.com/bigtangle/server.git
cd server/helper/divers
sh db.sh
sh bigtangle.sh
```

3. **Local Development**:
```bash
git clone https://github.com/bigtangle/server.git
cd server
mvn -DskipTests=true clean install
java -jar layer0-server/target/layer0-server-0.5.0-exec.jar
```

## Project Structure
- **bigtangle-core**: Core blockchain implementation
- **layer0-server**: Layer 0 server runtime
- **layer1-server**: Layer 1 server runtime
- **layer0-mcmc**: Layer 0 MCMC runtime
- **layer1-mcmc**: Layer 1 MCMC runtime
- **bigtangle-subtangle**: Subtangle implementation
- **helper**: Deployment and testing scripts

> **Note**: The seed/discovery service (`bigtangle-seeds`) lives in its own
> repo: [bigt-ai-platform/seeds](https://github.com/bigt-ai-platform/seeds).

## Testing
The project includes extensive test resources including:
- PostgreSQL test databases
- Protocol definitions
- Unit tests
- Integration tests

## Requirements
- Java 25
- Maven 3.6+
- Docker (for containerized deployment)
- PostgreSQL (for production deployment)
