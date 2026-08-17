# Validator Setup Scripts

Per-validator production setup, used by the migration plan
(`helper/prod/production-migration-plan.md`, Phase 4).

## Layout

```
validators/
  common.env             shared config (docker images, DB, PoS, stake, seeds)
  validator_common.sh    shared functions (db/start/stake/activate/verify)
  generate_keys.sh       produce node-<i>/validator.env credentials
  validator.env.example  template for per-node secrets
  node-<i>/setup.sh      per-validator entry point: setup.sh <phase>
  node-<i>/validator.env gitignored; holds that node's key (generated, not committed)
```

## Quickstart

1. Build the images (`helper/deploy.sh` from repo root), then set `SERVER_IMAGE` /
   `MCMC_IMAGE` in `common.env` (default: `ghcr.io/bigt-ai-platform/layer0-{server,mcmc}`).
   By default `DOCKER_NETWORK=host` so the DB on localhost stays reachable and the
   node's ports bind directly on the host.

2. Generate validator credentials:

   ```bash
   cd helper/prod/validators
   N_VALIDATORS=4 ./generate_keys.sh
   ```

3. Edit each `node-<i>/validator.env` and set `NODE_HOST` to that node's
   reachable IP/hostname.

4. Edit `common.env`: set `DB_*`, `SEED_HOSTS` (the `host:serverPort` list of
   every validator), `GENESIS_CSV` (the migration distribution CSV, identical on
   every node), and `FUND_MODE=genesis` for production.

## Phased bootstrap (production ordering)

Run the phases **in order across ALL nodes** on each node's own host. The mcmc
beacon producers MUST NOT start until every validator is staked and active:
beacon production ramps up as soon as the first validator activates, and later
stake deposits then land on a moving head and get reorged out (the 4-node
prodsim regression). Phases:

```bash
# 1) On EVERY node: create the DB + start layer0-server (no beacons yet).
node-<i>/setup.sh server

# 2) On EVERY node: fund (bootstrap mode only) + stake + activate THIS node's
#    validator, through its own API (getValidators grows 1,2,3,…,N).
node-<i>/setup.sh stake

# 3) On EVERY node: start the layer0-mcmc beacon producer — only now, with the
#    full active set agreed everywhere.
node-<i>/setup.sh mcmc

# 4) From ANY node: cross-node acceptance (validators == N everywhere, beacon
#    confirmed on every node, chainlengths within one epoch).
node-<i>/setup.sh verify
```

`REQUESTER` and `POS_GOSSIP_PEERS`/`GOSSIP_SEEDS` are derived from `SEED_HOSTS`
as a FULL mesh: a node pulls missing beacon parents only from its configured
requester, so a single/self requester stalls the bootstrap node at the first
missing parent and it confirms zero beacons.

## Notes

- One PostgreSQL database per node (`layer0`, `layer0_1`, …) and one
  `layer0-server` per DB.
- The validator key must be configured on **both** processes:
  - `layer0-server` runs with `--pos.dutyEnabled=false` (holds the key for
    `stakeDeposit` authorization only),
  - `layer0-mcmc` runs with `--pos.dutyEnabled=true` (proposes/attests).
- `stakeDeposit` no longer accepts a `privateKey` field — it signs with the
  server's configured key, so each validator stakes via **its own** node's API.
- `FUND_MODE=bootstrap` requires `FUND_ENABLED=true` on the server and mints
  coins unauthenticated — test/bootstrap only; prefer `FUND_MODE=genesis`
  (validators funded in the genesis distribution) for production.
