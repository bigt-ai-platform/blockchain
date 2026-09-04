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
- "Pay from genesis" mechanism: the genesis coinbase mints *confirmed*
  UTXOs whose `blockHash` = the genesis hash, with per-address values
  (the old `FundAddressesController.fund` faucet that used to do this was
  removed from the server).
- Validator activation: `fund → stakeDeposit(≥32,000,000 BIG) → activateValidator`.
  Reference impl: `helper/prod/validators/` phased `setup.sh` (server → stake →
  verify) and the cutover runbook.
- Seeds: `MainNetParams.dnsSeeds` (one enrtree) + `serverSeeds()` (IP list);
  discovery service lives in the separate `bigt-ai-platform/seeds` repo.

---

## Phase 0 — Freeze & decisions

- Freeze the legacy chain at a specific height/hash; stop writes (note the cutoff).
- Distribution is genesis-baked (Phase 3): the `/fundAddresses` faucet was
  removed from the server, so there is no bootstrap alternative.
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
   → `layer0-server-0.6.0-exec.jar`.
2. Node 0 DB + first start (schema creation) exactly as `prod.md` §1–§4:
   - PostgreSQL `layer0`, user `root`.
   - `layer0-server` with `--server.createtable=true --server.net=Mainnet --server.chain=L0
     --pos.validatorKey=<KEY_0> --pos.dutyEnabled=true --server.requester=http://127.0.0.1:8081 …`.
3. Confirm genesis created and `getChainNumber` returns 0 (no beacon yet).

## Phase 3 — Pay from genesis to snapshot addresses

Bake the snapshot into the genesis coinbase (the only distribution mechanism
since the `/fundAddresses` faucet was removed).

- Use the new `UtilGeneseBlock.createGenesis(params, List<GenesisOutput>)`:
  one coinbase output **per snapshot entry** (amount + base58 address or pubkey
  hex), instead of the single `genesisPub` output. The old single-key path stays
  intact for backward compatibility.
- Supply the snapshot to `MainNetParams` (load `genesisPub`-style distribution
  from a bundled resource/JSON) so the genesis block deterministically reproduces
  the legacy balances.
- Verify: `SUM(outputs.coinvalue)` over genesis outputs == `10^17`.

> The old Option B (`fundAddresses` bootstrap) is gone: the faucet was removed
> from the server, so genesis-baking is the only distribution mechanism. To
> sanity-check a snapshot first, bake it into a throwaway testnet genesis.

## Phase 4 — Per-validator setup shell scripts

Generated under `helper/prod/validators/`:

- `common.env` — shared config (ports base, DB credentials, docker image, seed nodes).
- `generate_keys.sh` — uses `ValidatorKeyTool` to produce `POS_VALIDATOR_KEY` /
  `VALIDATOR_PUBKEY` (and address) for each validator.
- `node-<i>/validator.env` — gitignored; holds that node's private seed.
- `node-<i>/setup.sh <phase>` — phased per-node setup: `server` (create DB + start
  `layer0-server`, validator duties on), `stake` (fund → `stakeDeposit` →
  `activateValidator`), `verify` (cross-node acceptance). Run the phases
  **in order across ALL nodes**; beacon duties run on the server itself, so the
  staking window completes before beacons move the head (see
  `validators/README.md` and the cutover runbook). `REQUESTER` /
  `POS_GOSSIP_PEERS` / `GOSSIP_SEEDS` derive from `SEED_HOSTS` as a full mesh so
  every node can pull missing beacon parents.

## Phase 5 — Seeds / network formation

1. Designate 2–3 seed nodes from the validator hosts (discovery + DAG sync only).
2. Set `MainNetParams.serverSeeds()` to the seed IPs and `dnsSeeds` to the enrtree
   of the seed bootstrap; update `bigt-ai-platform/seeds` to answer for those hosts.
3. On **every** node set `--server.requester=http://<seed>:8081` and
   `--pos.gossipPeers="<seed0>,<seed1>,…"` so attestations/slashing reach all.
4. Verify cross-node sync: each node's `getChainNumber` and `getValidators`
   converge to the same set.

## Phase 6 — Verify & cutover

- `getChainNumber` advances (slots 12 s × 32 slots/epoch).
- `getValidators` == the intended N-validator active set.
- Spot-check migrated balances via `getBalances`.
- Full audit: `SUM(coinvalue)` of open outputs == `10^17`; every snapshot address present.
- Enable TLS (`SSL/KEYSTORE`) before public exposure (no faucet flag exists
  anymore — funding is genesis-only).

---

## Required code changes

| Change | File | Status |
|--------|------|--------|
| Per-address genesis distribution | `UtilGeneseBlock.createGenesis(params, List<GenesisOutput>)` | implemented |
| Validator key tool (generate/pubkey from seed) | `net.bigtangle.tools.ValidatorKeyTool` | implemented |
| Snapshot → `MainNetParams` genesis distribution | `MainNetParams` (resource/JSON hook) | **TODO** (operator supplies list) |
