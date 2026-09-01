# Production Go-Live Plan — 3-Validator Mainnet (s1001, s1002, hk1002)

Companion to `prod.md` (deployment), `production-migration-plan.md` (legacy →
PoS genesis) and `cutover-runbook.md` (the authoritative launch runbook — this
plan maps it onto the three production hosts).

## Node placement

| Node index | Host     | Role before go-live                        | Role after go-live            |
|-----------:|----------|--------------------------------------------|-------------------------------|
| 0          | `s1001`  | Legacy BIG prod node (`p.bigtangle.org`, MySQL `bigtangle-mysql`) | Validator node-0 + snapshot source |
| 1          | `s1002`  | (free / other workloads)                   | Validator node-1              |
| 2          | `hk1002` | (free)                                     | Validator node-2 (geo diversity) |

Assumption: "rescaled" = each host is re-provisioned (or extended) to run
**PostgreSQL 16 + `layer0-server`** per `prod.md` §1–§4. Ports per index
(default scheme): API `8081+i`, peer UDP/TCP `30307+2i / 30308+2i`, gossip
`9095+2i`.

Decisions to confirm before Phase 0 (record answers in the launch ticket):

- [ ] Distribution mechanism: **Option A** — bake legacy balances into genesis
      via `GENESIS_CSV` (`FUND_MODE=genesis`). Recommended; `fundAddresses`
      must never be enabled in prod.
- [ ] Validator funding source: the 3 validator stakes (≥ 32 BIG each) are
      carved out of the operator's genesis balance (operator transfers to each
      validator address post-genesis) so the CSV still sums to exactly
      `10^17` satoshis.
- [ ] Network: WireGuard mesh with `s2001` as hub (`addwg.sh`), mesh IPs as
      `NODE_HOST` (e.g. s1001=10.8.0.11, s1002=10.8.0.12, hk1002=10.8.0.13).
- [ ] Public DNS: `p.bigtangle.org` keeps serving the legacy API until
      cutover is accepted, then repoints to s1001's new API/TLS port.

---

## Phase 0 — Pre-flight (all 3 hosts)

1. **Freeze legacy chain** on s1001: stop writes, record final height + cutoff
   timestamp. Announce the cutoff to users.
2. **OS/infra prep** on s1001, s1002, hk1002:
   - Docker + PostgreSQL 16 (`postgres:16` container or package).
   - **NTP discipline (chrony/systemd-timesyncd)** — verify < 100 ms skew.
     Lesson from the test mesh: a node with persistent clock drift gets
     dropped from the validator set.
   - Firewall: allow only `8081..8083`, peer/gossip ports, WireGuard; DB
     (5432) loopback/WG only.
3. **WireGuard mesh**: join all three via `sudo helper/prod/addwg.sh
   10.8.0.<n>`; confirm hub 10.8.0.1 reachable from each host.
4. **Build & images**: `mvn package -DskipTests`, `helper/deploy.sh`, push
   `ghcr.io/bigt-ai-platform/layer0-server:<tag>`; pull the tag on all hosts.
   Pin `IMAGE_TAG` in `common.env`.
5. **Run the full test suite** on the release binary: `bash helper/testall.sh`.

## Phase 1 — Snapshot legacy supply (on s1001)

1. Back up the legacy DB (`mysqldump` of `info`) — keep it even after s1001 is
   repurposed.
2. Produce the deterministic distribution CSV (address,pubkey,value, ordered
   by address). `genesis.sh` reads PostgreSQL; the legacy node is MySQL —
   either adapt the one SELECT to MySQL (`CONV(HEX(coinvalue),16,10)` decode,
   as `prodtoken.sh` does) or restore the dump into PostgreSQL first.
3. **Reconcile**: `SUM(value)` must equal exactly `100000000000000000`
   (10^17 satoshis = 100 bn BIG). Every address present.
4. Add the 3 validator funding rows (per the decision above) and re-check the
   total.
5. Distribute `GenesisOutput.csv` to all three hosts — it must be byte-identical
   everywhere (same genesis hash on every node).

## Phase 2 — Provision the 3 validators

1. Generate credentials: `cd helper/prod/validators && N_VALIDATORS=3
   ./generate_keys.sh` (or `addnode.sh add` per host). Back up the
   `node-*/validator.env` seeds **offline** (they are the stake keys).
2. Per node edit `node-<i>/validator.env`: `NODE_HOST=<mesh IP>`.
3. Edit `common.env`:
   - `SEED_HOSTS="10.8.0.11:8081,10.8.0.12:8082,10.8.0.13:8083"`
   - `GOSSIP_SEEDS="10.8.0.11:9095,10.8.0.12:9097,10.8.0.13:9099"`
   - `FUND_MODE=genesis`, `FUND_ENABLED=false`
   - `GENESIS_CSV=/path/GenesisOutput.csv`, `STORE_DOMAIN=core`
   - `API_KEY=<openssl rand -hex 32>`, non-default `DB_PASSWORD`
4. Per node generate a TLS keystore: `helper/prod/generate_keystore.sh`
   (`SSL=true`, `KEYSTORE`, `KEYSTOREPW`, `KEYSTORETYPE=PKCS12`).
5. Snapshot/backup each fresh host state (`helper/prod/backup.sh backup`).

## Phase 3 — Coordinated bootstrap (the ordering matters)

Run phases **in order across ALL nodes** (stake-before-beacons constraint):

```bash
# 1) on EVERY host (s1001 → node-0, s1002 → node-1, hk1002 → node-2):
node-<i>/setup.sh server     # DB + layer0-server, duties on, createtable=true

# check: identical genesis hash, getChainNumber == 0 on all 3
# 2) operator transfers 32+ BIG to each validator address (genesis balances)
#    then on EVERY host:
node-<i>/setup.sh stake      # stakeDeposit → activateValidator via own API

# 3) from any node, after all 3 are staked:
node-<i>/setup.sh verify     # validators==3 everywhere, same finalized root
```

Checkpoint: `getValidators` grows 1 → 2 → 3 on every node.

## Phase 4 — Beacon soak & activation height

1. Watch 2–3 epochs (≈ 20 min): `getChainNumber` advances, only the
   slot-selected proposer mints each beacon, `addnode.sh status` shows equal
   chainlengths.
2. Let the chain cross `POS_BEACON_SLOTDATA_ACTIVATION = 1024` (≈ 3.4 h at
   12 s slots) with **identical binaries on all nodes** — record the build
   hash in the launch ticket.
3. Watch justification/finality in logs: `docker logs node-0-server | grep -iE
   "justif|final"`.

## Phase 5 — Security hardening gate (before public exposure)

On **every** node, all must pass:

- [ ] `curl -X POST http://127.0.0.1:8081/fundAddresses …` → refused
      (`FUND_ENABLED=false`; genesis funding only).
- [ ] PoS endpoints require `X-Api-Key` (`stakeDeposit`, `activateValidator`,
      `processWithdrawal`, `setValidatorKey`).
- [ ] TLS live on the public port; `server.requester` / `pos.gossipPeers`
      rotated to `https://`.
- [ ] No `SECURITY:` warnings in any node's startup log (API key, TLS, DB
      password, fundEnabled) — each one is a launch blocker.
- [ ] DB ports not exposed publicly; secrets only as env vars, never CLI args.

## Phase 6 — Publication / seeds

1. Update `MainNetParams.serverSeeds()` + `dnsSeeds` and the
   `bigt-ai-platform/seeds` service to answer for the three nodes.
2. Repoint `p.bigtangle.org` (s1001) to the new API; set
   `server.corsAllowedOrigins` if a web app is served cross-origin.
3. Decommission the legacy stack on s1001 (stop `bigtangle-mysql` etc. after
   final backup; ports freed).

## Phase 7 — Post-cutover audit & operations

- [ ] Supply audit: `SUM(coinvalue)` of open `bc` outputs == `10^17`; every
      snapshot address payable via `getBalances`.
- [ ] `getValidators` == the intended 3-validator active set on all nodes;
      no validator below its stake.
- [ ] Monitoring: `addnode.sh status` (cron), `forkcheck.sh`, log alerts on
      `SECURITY:` / non-advancing `getChainNumber` / diverging finalized roots.
- [ ] Backups: nightly `backup.sh` per host; validator seeds + TLS keystores
      in offline cold storage.
- [ ] Run `prodbench.sh` once for a throughput baseline.

## Rollback (per `cutover-runbook.md`)

The chain is unlaunched until real value is in — "rollback" = restart from
genesis: stop all, drop DBs, fix, re-run Phases 3–5 (genesis is
deterministic). **Never** let nodes cross height 1024 with mixed binaries; if
any rollback trigger fires (chain stalls, validator sets differ, fork, supply
drift) stop and restart from genesis.

## Rollback triggers

| Signal | Check |
|--------|-------|
| `getChainNumber` stalls on any node | `addnode.sh status` |
| Validator sets differ across nodes | `getValidators` |
| Two proposers for one slot (fork) | server logs |
| Supply ≠ 10^17 | audit SQL |
