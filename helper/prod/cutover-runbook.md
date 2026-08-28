# PoS Cutover Runbook — Mainnet Launch

Executes Phase 4 of `plan/pos-production-readiness.md` end-to-end. Companion to
`prod.md` (per-node deployment) and `production-migration-plan.md` (legacy BIG →
PoS genesis distribution). **Run this once in staging before mainnet.**

All consensus changes are `[BREAKING]` and are gated behind the on-chain
attestation activation height (below). The network has NOT launched, so the
fork gate is a *chainlength* gate, not a hot fork of a live chain.

---

## 0. Pre-flight checks

- [ ] `mvn package -DskipTests` builds `layer0-server-0.6.0-exec.jar`
      (or the Docker image via `helper/deploy.sh`).
- [ ] Snapshot reconciled: `SUM(coinvalue)` of open BIG outputs == `10^17`
      (verified by `helper/prod/genesis.sh`).
- [ ] Validator keys generated (`helper/prod/validators/generate_keys.sh`),
      `POS_VALIDATOR_KEY`/`VALIDATOR_PUBKEY` per node, `NODE_HOST` set.
- [ ] Staging network reachable, ports unique per node (see `prod.md` §6).
- [ ] Decide the **fork/activation height** once and record it (see §3).
- [ ] Back up each node's PostgreSQL (warm standby or `pg_basebackup` snapshot,
      or `helper/prod/backup.sh backup`) before the first node starts the chain.
- [ ] Generate a per-deployment API key (`openssl rand -hex 32`) and set
      `API_KEY=` in every `node-<i>/validator.env` — the sensitive PoS endpoints
      must never run open on Mainnet.
- [ ] Generate deployment TLS keystores (`helper/prod/generate_keystore.sh`);
      never reuse the development keystore from the source tree.

---

## 1. Genesis bootstrap (chain epoch 0 window)

1. Start the `layer0-server` processes (one per node, unique DB/ports,
   `--pos.dutyEnabled=true`, `--server.createtable=true`; the validator seed
   travels as the `POS_VALIDATOR_KEY` env var and the API key as
   `SERVER_APIKEY` — both come from `validator.env`, never CLI args).
   Validator duties run on the server itself, so beacons start as soon as the
   first validator is active — complete the staking window (§2) promptly.
2. Confirm each node: `getChainNumber` == `0` and the genesis hash is identical
   on every node (same genesis distribution ⇒ same genesis block).

> Why the staking window matters: beacon production ramps up as soon as the
> first validator is active. If beacons run before every node has staked, later
> `stakeDeposit`s land on a moving head and the stake blocks get reorged out
> during bootstrap (the four nodes stake on diverging chains and never converge
> on one active set). Staking all validators while the chain is still near
> genesis makes bootstrap deterministic.

## 2. Coordinated validator bootstrap

Use the phased per-validator scripts (`helper/prod/validators/`) — this is the
production version of the verified prodsim ordering:

```bash
# On EVERY node, in order:
node-<i>/setup.sh server    # 1) create DB + start layer0-server (validator duties on)
node-<i>/setup.sh stake     # 2) fund(genesis→skip)/stake/activate THIS node's validator
# 3) only after ALL validators are staked+active, from any node:
node-<i>/setup.sh verify    #    cross-node acceptance (validators == N everywhere)
```

`setup.sh stake` submits `stakeDeposit`/`activateValidator` to the node's **own**
API (the STAKE tx is signed with that node's configured validator key; the
scripts attach the `X-Api-Key` header automatically when `API_KEY` is set):

```bash
curl -X POST http://<node-i>:8081/stakeDeposit -H 'Content-Type: application/json' \
  -H 'X-Api-Key: <API_KEY>' \
  -d '{"pubkey":"<VALIDATOR_PUBKEY_i>","amount":"32000000"}'
curl -X POST http://<node-i>:8081/activateValidator -H 'Content-Type: application/json' \
  -H 'X-Api-Key: <API_KEY>' \
  -d '{"pubkey":"<VALIDATOR_PUBKEY_i>","epoch":0}'
```

Checkpoint per node: `getValidators` grows 1 → 2 → 3 → … → N, and after the last
validator every node reports the **same** active set (N).

## 3. Beacon production

1. The `layer0-server` processes already run with `--pos.dutyEnabled=true`, so
   once staking is complete each node proposes in its selected slots and attests
   (`--server.requester=<full requester mesh>`, `--pos.gossipPeers=<all server
   host:ports>`, `--gossip.peers=<gossip mesh>`). The scripts derive the full
   requester/gossip mesh from `SEED_HOSTS` — a bootstrap node with no (or
   self-only) requester confirms zero beacons.
2. Confirm the first beacon confirms: `getChainNumber` advances past 0 and
   `getValidators` stays N on every node.
3. Run at least 2–3 epochs (≈ 20 min at 12 s slots) before the activation height
   is reached, so the gossip fallback path is exercised first.

## 3b. Cross-node acceptance

From any node, run the acceptance check across all hosts:

```bash
node-<i>/setup.sh verify
```

This asserts every node reports the full active set (N, nothing slashed/reverted),
every node has confirmed at least one beacon, and the confirmed chainlengths
agree within one epoch. Run it before the fork height is reached.

## 4. Fork / activation height

On-chain (embedded) attestations become the source of truth for justification,
fork choice and rewards at chainlength

```
POS_BEACON_SLOTDATA_ACTIVATION = 1024   (chainlength)
```

Override only for testing: `-Dnet.bigtangle.pos.attestationActivation=<height>`
(system property; every node must use the **same** value, or
the gate must be identical on every node). Production uses the default 1024.

- Below the height: votes are read from the gossip view (pre-fork fallback).
- At/above the height: votes are read from the embedded on-chain attestation
  set — deterministic per chain height on every node.
- All `[BREAKING]` consensus changes (justification threshold, activation delay,
  slashing forms, rewards, leak, proposer boost) are shipped in the same binary
  and take effect behind this gate. Record the chosen height and the binary
  build hash in the cutover ticket.

## 5. Enable TLS

1. Provision a PKCS12 keystore per node with
   `helper/prod/generate_keystore.sh` (`SSL=true`,
   `KEYSTORE=<path>/ca.pkcs12`, `KEYSTOREPW=<generated>`, `KEYSTORETYPE=PKCS12`)
   and set `server.port` to the public TLS port. Never use the development
   keystore from the source tree — it is public.
2. Rotate `pos.gossipPeers`/`server.requester` to the `https://` URLs.
3. Restart both processes on each node; verify the health endpoint answers over
   TLS and DAG sync / attestation gossip still converge.

## 6. Disable `fundAddresses` + security warning audit

`server.fundEnabled` mints confirmed coins over an **unauthenticated** endpoint.
It must be `false` on every production node **before** any node is publicly
reachable.

Verification (must all pass, on **every** node):

```bash
# 1. Endpoint refused
curl -sf -X POST http://127.0.0.1:8081/fundAddresses \
  -H 'Content-Type: application/json' -d '{"addresses":[]}' \
  && echo "UNSAFE: fundAddresses still enabled" || echo "OK: disabled"

# 2. Chain advances (height strictly increasing, epoch boundaries passing)
curl -X POST http://127.0.0.1:8081/getChainNumber -H 'Content-Type: application/json' -d '{}'

# 3. Active set identical on all nodes
curl -X POST http://127.0.0.1:8081/getValidators -H 'Content-Type: application/json' -d '{}'

# 4. Finality advances (justified/finalized epochs increase) — check logs:
docker logs -f node-0-server | grep -iE "justif|final"
```

Also verify no `SECURITY:` startup warnings remain in each node's log — every
one of them (missing API key, fundEnabled, no TLS, default DB password) is a
launch blocker:

```bash
grep -i "SECURITY:" node-0-server.log && echo "UNSAFE: fix warnings above" || echo "OK: no security warnings"
```

## 7. Post-cutover audit

- `SUM(coinvalue)` over open outputs still == `10^17`; every snapshot address
  present via `getBalances`.
- `getValidators` == the intended N-validator set.
- No validator balance below its stake (rewards/voting intact).

---

## Rollback path

The chain has **not** launched, so "rollback" here means: restart from genesis
with a corrected binary — there is no live-chain state to unwind.

Before the first genesis block is broadcast:

1. Stop all processes; `docker compose down -v` (or drop the DBs). Volumes are
   the only state.
2. Fix the offending change and re-run §1–§6. Genesis is deterministic, so a
   re-run reproduces the same chain with the corrected code.

If a defect is found **after** the chain has started but **before** the
activation height (1024):

- Consensus below the gate still uses the gossip fallback; a node running an
  older compatible binary can rejoin and re-sync.
- Do **not** let any node cross the activation height with mixed binaries — the
  gate is deterministic only if every node enables it at the same height.
- Preferred: restart the whole network from genesis (no mainnet value at stake
  yet) rather than risk a split.

If a defect is found **after** the activation height:

- This is a hard fork of an unlaunched network; the only clean path is
  restart-from-genesis with the fix, re-running §1–§6. There is no downgrade
  path: post-gate binaries reject pre-gate beacon semantics, and stake/attestation
  records in a live `stake_deposits`/`pos_state` are not portable across a
  consensus change.
- Keep a full DB snapshot (from §0) per node so a restart can diff
  `SUM(coinvalue)` against the snapshot as a sanity check.

### Rollback triggers (when to stop and restart from genesis)

| Signal | Where |
|--------|-------|
| `getChainNumber` stops advancing on any node | `prod.md` §9 / logs |
| Validator sets differ across nodes | `getValidators` |
| Beacon proposals for the same slot from >1 node (fork) | server logs / slashing |
| `SUM(coinvalue)` drift from `10^17` | audit SQL |

---

## Verification tooling (already green)

- Phased 4-node bootstrap: `helper/prod/validators/` (`setup.sh` phases
  server → stake → verify) verified against the stake-before-beacons
  bootstrap regression; finality/health checks in the `verify` phase.
- PoS suite: `bash helper/testall.sh` (bigtangle-core + bigtangle-servercore,
  incl. `PosConsensusHardeningTest` and `MempoolServiceTest`).
