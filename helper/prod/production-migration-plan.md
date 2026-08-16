# Production Migration Plan — Legacy BIG → Proof-of-Stake L0

Companion to `prod.md`. Covers the five required steps:

1. Get the existing BIG supply with **all addresses/balances** (snapshot).
2. Start / initialize the PoS chain (genesis).
3. Pay **from genesis** to those addresses (distribution).
4. Write a setup shell script **per validator**.
5. Configure **seeds** to form the network.

## Facts from the code (constraints)

- Total supply is fixed: `NetworkParameters.BigtangleCoinTotal = 10^(11+6) = 10^17`
  smallest units = **100,000,000,000 BIG**, token id `"bc"` (`BIGTANGLE_TOKENID`).
  The migration must sum to exactly this.
- Genesis (`UtilGeneseBlock.createGenesis`) mints the whole supply to
  `params.genesisPub` (single pubkey, or comma-separated pubkeys → one output or
  M-of-N multisig). **Per-address amounts need the new overload** (see Phase 3).
- Snapshot source: legacy chain's `outputs` table
  (`toaddress, coinvalue, tokenid, confirmed, spent`).
- "Pay from genesis" mechanism: `FundAddressesController.fund` mints *confirmed*
  UTXOs whose `blockHash` = the genesis hash, with per-address values. Gated by
  `FUND_ENABLED` (`server.fundEnabled`).
- Validator activation: `fund → stakeDeposit(≥32,000,000 BIG) → activateValidator`.
  Reference impl: `layer0-mcmc/src/test/java/net/bigtangle/mcmc/prodsim/ProdSimBootstrap.java`.
- Seeds: `MainNetParams.dnsSeeds` (one enrtree) + `serverSeeds()` (IP list);
  discovery service lives in the separate `bigt-ai-platform/seeds` repo.

---

## Phase 0 — Freeze & decisions

- Freeze the legacy chain at a specific height/hash; stop writes (note the cutoff).
- Choose the distribution mechanism (Phase 3): **Option A** bake into genesis
  (recommended) vs **Option B** `fundAddresses` bootstrap.
- Decide the validator set (N nodes) and their host IPs/ports.
- Confirm total: `SUM(coinvalue)` of open BIG outputs must equal `10^17`.

## Phase 1 — Snapshot existing BIG (all addresses)

1. Export from the legacy node's PostgreSQL:

   ```sql
   SELECT toaddress AS address, SUM(coinvalue) AS balance, COUNT(*) AS utxos
   FROM outputs
   WHERE confirmed = true AND spent = false AND tokenid = 'bc'
   GROUP BY toaddress
   ORDER BY balance DESC;
   ```

   (Equivalent API: `OutputService.getOpenAllOutputs("bc")`.)

2. Capture the **pubkey** per address where recoverable (needed to rebuild P2PKH
   scripts). If only the base58 address is available, the genesis distribution
   can fall back to `Address.fromBase58` (P2PKH by hash160).

3. Reconcile: `SUM(balance)` must equal `10^17`.

4. Emit a canonical JSON file, e.g. `migration/snapshot.json`:

   ```json
   [ {"address":"…","pubkey":"…","value":1234567890123}, … ]
   ```

## Phase 2 — Start / initialize the PoS chain

1. Build: `mvn package -DskipTests`
   → `layer0-server-0.6.0-exec.jar`, `layer0-mcmc-0.6.0-exec.jar`.
2. Node 0 DB + first start (schema creation) exactly as `prod.md` §1–§4:
   - PostgreSQL `layer0`, user `root`.
   - `layer0-server` with `--server.createtable=true --server.net=Mainnet --server.chain=L0 …`.
   - `layer0-mcmc` with `--pos.validatorKey=<KEY_0> --server.requester=http://127.0.0.1:8081 …`.
3. Confirm genesis created and `getChainHeight` returns 0 (no beacon yet).

## Phase 3 — Pay from genesis to snapshot addresses

**Option A (recommended, consensus-clean): bake the snapshot into the genesis coinbase.**

- Use the new `UtilGeneseBlock.createGenesis(params, List<GenesisOutput>)`:
  one coinbase output **per snapshot entry** (amount + base58 address or pubkey
  hex), instead of the single `genesisPub` output. The old single-key path stays
  intact for backward compatibility.
- Supply the snapshot to `MainNetParams` (load `genesisPub`-style distribution
  from a bundled resource/JSON) so the genesis block deterministically reproduces
  the legacy balances.
- Verify: `SUM(outputs.coinvalue)` over genesis outputs == `10^17`.

**Option B (bootstrap, faster but test-only semantics): keep genesis as-is, replay via `fundAddresses`.**

- On node 0, set `FUND_ENABLED=true` **temporarily**, POST the snapshot, then
  **set `FUND_ENABLED=false` and restart** before the node is publicly reachable
  (the endpoint mints coins unauthenticated — `prod.md` §Security):

  ```bash
  curl -X POST http://127.0.0.1:8081/fundAddresses -H 'Content-Type: application/json' \
    -d '{"addresses":[{"address":"…","value":…,"pubkey":"…"}, …]}'
  ```

- Mints confirmed UTXOs keyed to the genesis hash (no beacon needed — the PoS
  chicken-and-egg the prodsim bootstrap works around).

> Recommend A for mainnet auditability; use B to sanity-check the snapshot first.

## Phase 4 — Per-validator setup shell scripts

Generated under `helper/prod/validators/`:

- `common.env` — shared config (ports base, DB credentials, docker image, seed nodes).
- `generate_keys.sh` — uses `ValidatorKeyTool` to produce `POS_VALIDATOR_KEY` /
  `VALIDATOR_PUBKEY` (and address) for each validator.
- `node-<i>/validator.env` — gitignored; holds that node's private seed.
- `node-<i>/setup.sh` — per node: create DB → start `layer0-server` + `layer0-mcmc`
  (unique ports/DB/`pos.validatorKey`/`pos.gossipPeers`) → fund → `stakeDeposit` →
  `activateValidator` (idempotent via `getValidators`).

## Phase 5 — Seeds / network formation

1. Designate 2–3 seed nodes from the validator hosts (discovery + DAG sync only).
2. Set `MainNetParams.serverSeeds()` to the seed IPs and `dnsSeeds` to the enrtree
   of the seed bootstrap; update `bigt-ai-platform/seeds` to answer for those hosts.
3. On **every** node set `--server.requester=http://<seed>:8081` and
   `--pos.gossipPeers="<seed0>,<seed1>,…"` so attestations/slashing reach all.
4. Verify cross-node sync: each node's `getChainHeight` and `getValidators`
   converge to the same set.

## Phase 6 — Verify & cutover

- `getChainHeight` advances (slots 12 s × 32 slots/epoch).
- `getValidators` == the intended N-validator active set.
- Spot-check migrated balances via `getBalances`.
- Full audit: `SUM(coinvalue)` of open outputs == `10^17`; every snapshot address present.
- Enable TLS (`SSL/KEYSTORE`); disable `FUND_ENABLED` before public exposure.

---

## Required code changes

| Change | File | Status |
|--------|------|--------|
| Per-address genesis distribution | `UtilGeneseBlock.createGenesis(params, List<GenesisOutput>)` | implemented |
| Validator key tool (generate/pubkey from seed) | `net.bigtangle.tools.ValidatorKeyTool` | implemented |
| Snapshot → `MainNetParams` genesis distribution | `MainNetParams` (resource/JSON hook) | **TODO** (operator supplies list) |
