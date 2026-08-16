# Validator Setup Scripts

Per-validator production setup, used by the migration plan
(`helper/prod/production-migration-plan.md`, Phase 4).

## Layout

```
validators/
  common.env             shared config (docker images, DB, PoS, stake, seeds)
  validator_common.sh    shared functions (db/start/stake/activate)
  generate_keys.sh       produce node-<i>/validator.env credentials
  validator.env.example  template for per-node secrets
  node-<i>/setup.sh      per-validator entry point (identical; sources ./validator.env)
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
   every validator), and `FUND_MODE` (`genesis` or `bootstrap`).

5. On each node, run:

   ```bash
   node-<i>/setup.sh
   ```

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
