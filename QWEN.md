# BigTangle Blockchain Server - Project Context

## Project Overview

BigTangle is a distributed blockchain platform implementation written primarily in Java. It features a multi-module architecture with support for various blockchain operations including cryptocurrency transactions, token creation, smart contracts, order matching, and governance functions. The platform implements a unique dual-blockchain structure with trunk and branch blocks, supporting multiple block types for different operations.

### Key Components

- **Core Blockchain**: Implements the main blockchain protocol with proof-of-work consensus in the `bigtangle-core` module
- **Server Nodes**: Multiple node types including core servers (`bigtangle-server`), seed nodes (`bigtangle-seeds`), and subtangle servers (`bigtangle-subtangle`)
- **Wallet**: Secure cryptocurrency wallet implementation in `bigtangle-core`
- **Web Interface**: Web-based frontend in `bigtangle-web`
- **Kafka Bridge**: Kafka integration for streaming in `bigtangle-kafkabridge`

### Technical Stack

- **Languages**: Java (main), C++ (proof-of-work via secp256k1 library)
- **Build System**: Maven
- **Runtime**: Java 17
- **Database**: PostgreSQL (primary), MySQL (alternative)
- **Messaging**: Kafka integration
- **Web Framework**: Spring Boot
- **Caching**: Hazelcast
- **Object Storage**: MinIO

### Architecture

The platform implements a unique blockchain architecture with:

- **Dual Block Structure**: Each block has both a previous trunk block hash and a previous branch block hash, creating a two-dimensional blockchain structure
- **Multiple Block Types**: Different block types for specific operations (transfer, reward, token creation, contracts, orders, governance, etc.)
- **Modular Design**: Separate modules for core functionality, server implementation, seeds, and subtangle operations
- **Kafka Integration**: For streaming and distributed processing
- **MinIO Integration**: For file storage and data management

## Building and Running

### Prerequisites
- Java 17
- Maven 3.6+
- Docker (for containerized deployment)
- PostgreSQL (for production deployment)

### Build Process
```bash
# Clone the repository
git clone https://github.com/bigtangle/server.git
cd server

# Build the project
mvn -DskipTests=true clean install
```

### Running the Server
```bash
# Local development run
java -Xmx5028m --add-exports java.base/sun.nio.ch=ALL-UNNAMED -jar bigtangle-server/target/bigtangle-server-0.5.0-exec.jar

# Or run directly with Maven
cd bigtangle-server
mvn spring-boot:run
```

### Docker Deployment
```bash
# Build Docker image
docker build -t bigtangle -f helper/bigtangle/Dockerfile .

# Run with helper script
sh helper/bigtangle/testdocker.sh
```

### Production Deployment
```bash
# Set up database
sh helper/divers/db.sh

# Run the server
sh helper/divers/bigtangle.sh
```

## Configuration

The application is configured through `application.yml` files in each module with environment variable overrides. Key configuration areas include:

- Server port and network settings
- Database connection parameters
- Kafka streaming configuration
- Mining and scheduling parameters
- SSL settings
- MinIO object storage configuration

## Development Conventions

- Java 17 is required with specific JVM exports for compatibility
- Maven is used for dependency management and building
- Spring Boot conventions are followed for web services
- The project follows a multi-module Maven structure
- Cryptographic operations use the secp256k1 library for elliptic curve operations
- Database migrations and schema management are handled through the application

## Key Features

- **Multiple Block Types**: Support for different operations through specialized block types
- **Token Creation**: Native support for creating and managing custom tokens
- **Smart Contracts**: Contract execution and event handling
- **Order Matching**: Built-in exchange functionality with order books
- **Governance**: On-chain governance mechanisms
- **File Storage**: Blockchain-based file storage capabilities
- **Cross-tangle Operations**: Support for cross-chain transactions
- **Proof of Work**: Traditional PoW consensus mechanism
- **Equihash Implementation**: Alternative PoW algorithm support

## Project Structure

- **bigtangle-core**: Core blockchain implementation with blocks, transactions, crypto, and wallet functionality
- **bigtangle-server**: Main server implementation with Spring Boot integration
- **bigtangle-seeds**: Seed node implementation for network discovery
- **bigtangle-subtangle**: Subtangle implementation for specialized chains
- **bigtangle-web**: Web interface for the blockchain
- **bigtangle-kafkabridge**: Kafka integration for streaming
- **helper**: Deployment scripts and Docker configurations
- **bigtangle-docker**: Docker-related configurations

## Testing

The project includes extensive test resources including:
- Unit tests using JUnit 5
- Integration tests
- PostgreSQL test databases
- Protocol definitions for testing
- Performance tests (excluded from default runs)

## Special Considerations

- The project requires the secp256k1 native library for cryptographic operations
- JVM arguments `--add-exports java.base/sun.nio.ch=ALL-UNNAMED` are required for proper operation
- The blockchain uses a unique dual-block structure that differs from traditional single-chain blockchains
- Multiple database types are supported (PostgreSQL, MySQL) with configuration switches
- The platform supports both mainnet and testnet configurations