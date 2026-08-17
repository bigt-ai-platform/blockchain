# PoS Cutover Runbook — Mainnet Launch

Executes Phase 4 of `plan/pos-production-readiness.md` end-to-end. Companion to
`prod.md` (per-node deployment) and `production-migration-plan.md` (legacy BIG →
PoS genesis distribution). **Run this once in staging before mainnet.**

All consensus changes are `[BREAKING]` and are gated behind the on-chain
attestation activation height (below). The network has NOT launched, so the
fork gate is a *chainlength* gate, not a hot fork of a live chain.

---

## 0. Pre-flight checks

- [ ] `mvn package -DskipTests` builds `layer0-server-0.6.0-exec.jar` +
      `layer0-mcmc-0.6.0-exec.jar` (or the Docker images via `helper/deploy.sh`).
- [ ] Snapshot reconciled: `SUM(coinvalue)` of open BIG outputs == `10^17`
      (verified by `helper/prod/genesis.sh`).
- [ ] Validator keys generated (`helper/prod/validators/generate_keys.sh`),
      `POS_VALIDATOR_KEY`/`VALIDATOR_PUBKEY` per node, `NODE_HOST` set.
- [ ] Staging network reachable, ports unique per node (see `prod.md` §6).
- [ ] Decide the **fork/activation height** once and record it (see §3).
- [ ] Back up each node's PostgreSQL (warm standby or `pg_basebackup` snapshot)
      before the first node starts the chain.

---

## 1. Genesis bootstrap (chain epoch 0 window)

1. Start **only** the `layer0-server` processes (one per node, unique DB/ports,
   `--pos.dutyEnabled=false`, `--pos.validatorKey=<key_i>`, `--server.createtable=true`).
   Do **not** start the mcmc beacon producers yet.
2. Confirm each node: `getChainHeight` == `0` and the genesis hash is identical
   on every node (same genesis distribution ⇒ same genesis block).

> Why servers-only first: beacon production ramps up as soon as the first
> validator is active. If the mcmc proposers were up, later `stakeDeposit`s land
> on a moving head and the stake blocks get reorged out during bootstrap (the
> four nodes stake on diverging chains and never converge on one active set).
> Staking all validators while the chain is still at genesis makes bootstrap
> deterministic. This is exactly what the 4-node prodsim
> (`helper/prodsim/run.sh`) does and is verified green.

## 2. Coordinated validator bootstrap

For every validator `i`, submit `stakeDeposit`/`activateValidator` to **its own**
node (the STAKE tx is signed with that node's configured `pos.validatorKey`):

```bash
# stake (>= 32,000,000 satoshis = 32 BIG)
curl -X POST http://<node-i>:8081/stakeDeposit -H 'Content-Type: application/json' \
  -d '{"pubkey":"<VALIDATOR_PUBKEY_i>","amount":"32000000"}'

# activate at chain epoch 0 (genesis bootstrap window → immediate activation)
curl -X POST http://<node-i>:8081/activateValidator -H 'Content-Type: application/json' \
  -d '{"pubkey":"<VALIDATOR_PUBKEY_i>","epoch":0}'
```

Checkpoint per node: `getValidators` grows 1 → 2 → 3 → … → N, and after the last
validator every node reports the **same** active set (N). Only proceed when the
set has converged on every node.

## 3. Start beacon production

1. Start the `layer0-mcmc` processes (`--pos.dutyEnabled=true`,
   `--server.requester=http://<seed-node>:8081`,
   `--pos.gossipPeers=<all server host:ports>`, `--server.createtable=false`).
2. Confirm the first beacon confirms: `getChainHeight` advances past 0 and
   `getValidators` stays N on every node.
3. Run at least 2–3 epochs (≈ 20 min at 12 s slots) before the activation height
   is reached, so the gossip fallback path is exercised first.

## 4. Fork / activation height

On-chain (embedded) attestations become the source of truth for justification,
fork choice and rewards at chainlength

```
POS_BEACON_SLOTDATA_ACTIVATION = 1024   (chainlength)
```

Override only for testing: `-Dnet.bigtangle.pos.attestationActivation=<height>`
(system property; both server and mcmc processes must use the **same** value, or
the gate must be identical on every node). Production uses the default 1024.

- Below the height: votes are read from the gossip view (pre-fork fallback).
- At/above the height: votes are read from the embedded on-chain attestation
  set — deterministic per chain height on every node.
- All `[BREAKING]` consensus changes (justification threshold, activation delay,
  slashing forms, rewards, leak, proposer boost) are shipped in the same binary
  and take effect behind this gate. Record the chosen height and the binary
  build hash in the cutover ticket.

## 5. Enable TLS

1. Provision a PKCS12 keystore per node (`SSL=true`,
   `KEYSTORE=<path>/ca.pkcs12`, `KEYSTOREPW=…`, `KEYSTORETYPE=PKCS12`) and set
   `server.port` to the public TLS port.
2. Rotate `pos.gossipPeers`/`server.requester` to the `https://` URLs.
3. Restart both processes on each node; verify the health endpoint answers over
   TLS and DAG sync / attestation gossip still converge.

## 6. Disable `fundAddresses`

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
curl http://127.0.0.1:8081/getChainHeight

# 3. Active set identical on all nodes
curl -X POST http://127.0.0.1:8081/getValidators -H 'Content-Type: application/json' -d '{}'

# 4. Finality advances (justified/finalized epochs increase) — check logs:
docker logs -f node-0-mcmc | grep -iE "justif|final"
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
| `getChainHeight` stops advancing on any node | `prod.md` §9 / logs |
| Validator sets differ across nodes | `getValidators` |
| Beacon proposals for the same slot from >1 node (fork) | mcmc logs / slashing |
| `SUM(coinvalue)` drift from `10^17` | audit SQL |

---

## Verification tooling (already green)

- 4-node convergence: `helper/prodsim/run.sh` — bootstrap redesign verified
  (stake-all-before-mcmc), finality/health checks in `ProdSimVerification`.
- Single-DB remote harness: `layer0-mcmc/.../remote/remote.sh`
  (`RemoteEpochRewardTests`, `RemoteOrderTests`, `RemoteTokenTests`).
- PoS suite: `helper/testall.sh "PoSTest,StakeIT,ValidatorDutyTest,SlotTickServiceTest,RewardServiceTest"`.
