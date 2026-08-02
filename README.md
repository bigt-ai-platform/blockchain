# BigTangle Blockchain Server

BigTangle is a distributed blockchain platform with a two-layer consensus
architecture: **MCMC DAG** for transaction throughput + **PoS Beacon Chain**
for deterministic Casper FFG finality.

## Architecture

One Layer 0 (settlement) chain worldwide; many L1 application chains.

| Layer | Chains | Purpose |
|-------|--------|---------|
| **Layer 0** | `L0` | BIG mint, token creation, anchors |
| **L1** | `ordermatch`, `L1-contract`, `EVM`, `PAI`, `NFT`, `PAYMENT` | Application-specific |

Full design: [blockchain.md](blockchain.md)

## Technical Stack

- **Languages**: Java (main), C++ (secp256k1 native)
- **Build System**: Maven
- **Runtime**: Java 25
- **Database**: PostgreSQL 16
- **Messaging**: Kafka integration

## Modules

| Module | Port | Role |
|--------|------|------|
| `bigtangle-core` | — | Data model, crypto |
| `bigtangle-servercore` | — | Consensus engine, DB schema, services |
| `bigtangle-bridge` | — | Cross-layer anchors + peg logic |
| `layer0-server` | 8081 | L0 full node |
| `layer0-mcmc` | 8082 | L0 MCMC consensus |
| `l1-order-server` | 8083 | L1 order-match |
| `l1-order-mcmc` | 8084 | L1 order-match consensus |
| `l1-contract-server` | 8085 | L1 smart contracts |
| `l1-contract-mcmc` | 8086 | L1 contract consensus |
| `l1-evm-server` | 8093 | L1 EVM (Solidity) smart contracts |
| `l1-evm-mcmc` | 8094 | L1 EVM consensus |
| `l1-pai-server` | 8087 | L1 AI provider chain |
| `l1-pai-mcmc` | — | L1 PAI consensus |
| `l1-nft-server` | 8089 | L1 NFT chain |
| `l1-nft-mcmc` | — | L1 NFT consensus |
| `l1-payment-server` | 8091 | L1 transfer-only chain |
| `l1-payment-mcmc` | — | L1 payment consensus |

## Quick Start

```bash
# Start PostgreSQL
docker compose -f helper/docker-compose-base.yml up -d

# Run all tests
bash helper/testall.sh

# Start individual nodes
mvn -pl layer0-server spring-boot:run
mvn -pl l1-payment-server spring-boot:run -DCHAIN_ID=PAYMENT-US
```

## Key Features

- **MCMC + PoS consensus**: Probabilistic DAG tips + deterministic Casper finality
- **Multi-layer**: Isolated L1 chains with bridge peg-in/peg-out
- **Configurable L1 chain ID**: Run many instances via `CHAIN_ID` env var
- **Post-quantum crypto**: ML-DSA-87 (FIPS 204) signatures, with an optional height-gated SLH-DSA-SHA2-256s backstop
- **Fee pool**: Per-chain fee accumulation distributed to validators at epoch boundaries
- **Attack resilience**: Mempool double-spend + signature verification at ingress

## Requirements

- Java 25 (Temurin)
- Maven 3.6+
- Docker (for PostgreSQL)
- PostgreSQL 16

## Repositories

- **Server** (this repo): All chain modules
- **Seeds**: [bigt-ai-platform/seeds](https://github.com/bigt-ai-platform/seeds)
