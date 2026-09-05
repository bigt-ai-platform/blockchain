# L0 Reset Runbook — keep L1 (social) running

`prod.sh reset` wipes the L0 databases (fresh genesis) while the L1
`l1-social-server` pair on the same hosts **keeps its `social` databases and
keeps running**. No L1 wipe, no L1 redeploy, no consumer-group rotation.
`prod-social.sh` intentionally has no `reset` subcommand.

Companion to `prod.md` (deployment) and `golive-plan.md`.

---

## 1. What `prod.sh reset --yes` does (L0 only)

Per host (`prod.sh:485-525`):

- stops + removes the `l0-server` container;
- dedicated postgres (`l0eu-pg`): drops the container **and** wipes
  `/data/<pgc>`; pinned postgres (`PG_CONTAINERS` set): drops + recreates the
  `DB_NAME` database only;
- runs `cmd_up`: fresh DB mints the code-default genesis (total supply to
  the ceremony 2-of-3 multisig, `MainNetParams.genesisPub` — `GENESIS_CSV`
  defaults to empty; set it explicitly for legacy prodsim-style per-address
  minting), nodes restart with the latest image. Wallets are funded
  afterwards — see §6.

Consequences on L0: new genesis, chain height back to ~0, all L0 validator
deposits gone — **re-staking on the fresh L0 is required** (`prod.sh:511`).
Kafka consumer groups and topics are untouched.

---

## 2. Why L1 survives an L0 reset (verified, not assumed)

The SOCIAL chain is self-contained; every L0 coupling degrades to a harmless
no-op against a fresh L0:

1. **No L0 block import.** A non-minting L1 skips L0 CROSSTANGLE blocks;
   bridge state arrives via watchers, never via block sync
   (`SyncBlockService.java:432-442`). Milestones, reward chain and finality
   are computed from SOCIAL data only. SOCIAL genesis is code-deterministic
   (no CSV), so the kept DB stays internally consistent.
2. **Readiness gate passes trivially.** Startup waits only while
   `local < target`, where target is the requester's (L0) finalized length
   (`AbstractScheduleInitService.java:52-77`). After a reset the L0 target is
   ~0, far below the kept SOCIAL height — an L1 restart goes ready
   immediately (bounded by `-Dbigtangle.readinessTimeoutMinutes`, default 3).
   While L0 is still down the probe returns -1 and the wait is skipped.
3. **No cross-chain state adoption.** `CasperService.adoptFinalizedAnchor:543-562`
   verifies any peer-advertised checkpoint against the node's **own**
   chain-derived epoch boundary — an L0 hash can never match a SOCIAL
   boundary, so adoption is refused. The cold-start path only fires when the
   node's own finality has stalled (`SlotTickService.java:166-183`).
4. **AnchorWatcher is monotonic on local state.** It tracks height from the
   local latest anchor and asks L0 only for anchors above it
   (`AnchorWatcherService.java:93-124`). A fresh L0 returns nothing — silent
   no-op, no forced reorg. Later anchors for the continuing L1 heights flow
   normally; the reorg path is additionally guarded by the
   finalized-descendant check.
5. **Bridge minting is idempotent across the reset.**
   `BridgeService.processPegInFromL0:595-694` polls L0 over HTTP (no Kafka
   offsets) with replay protection via VaultRecords keyed by
   `(chainId, blockHash, index)` in the L1 store. A fresh L0 produces new
   block hashes, so pre-reset records are inert and new locks mint exactly
   once. L0 downtime only logs warn-and-skip.
6. **Kafka is chain-scoped.** Topics resolve to `bigtangle-*-SOCIAL` vs
   `bigtangle-*-L0` (`KafkaConfiguration.java:31-35`), so L0's post-reset
   records never enter L1 topics and L1's committed offsets stay valid.

---

## 3. Procedure

L1 needs **zero commands**. Order matters — L0 first, then verify L1:

1. Leave L1 alone: no `prod-social.sh down`, no `social` DB wipe, no env
   changes. Let the containers keep running through the L0 outage (SOCIAL
   duties continue; PegIn/Anchor watchers log warn-and-skip).
2. Reset L0 (destructive — requires `--yes`):
   `helper/prod/prod.sh reset --yes`
3. Wait for `helper/prod/prod.sh status` healthy on **all** L0 nodes
   (container running, API + HTTPS up) with finality advancing.
4. Run the genesis payout ceremony (§6): `genesis-payout.sh preflight`,
   `payout`, `verify`. Nothing downstream works until wallets hold funds.
5. Verify L1 with `helper/prod/prod-social.sh status` (containers still
   running, API + HTTPS up) and check `logs`: watchers resume once L0 is
   back. If `bridge.requireFinality` is on, minting additionally waits for
   fresh-L0 finality on its own.
6. Re-stake the L0 validators on the fresh L0 (fresh genesis = no deposits),
   fund the bridge vault, then peg-in L1.
7. Settle the stranded wrapped supply, see §4.

Do NOT do any of the following:

- do NOT drop/recreate the `social` databases (on s2001 it shares `l0-pg`
  with L0 — a container wipe there would be doubly wrong);
- do NOT rotate `SOCIAL_CONSUMERIDSUFFIX` (abandons valid offsets; replays
  the whole SOCIAL log for nothing);
- do NOT leave L1 nodes stopped for longer than the SOCIAL Kafka topic
  retention — their only block sources are the SOCIAL topics and the L0
  requester, which cannot serve SOCIAL history.

---

## 4. Known loss: pre-reset pegged-in funds cannot return to L0

**Peg-out runs on L0 against the L0 store** (`PegOutRetryService` — "the L0
peg-out operator", `L0AnchorHandler.java:158-162`,
`DispatcherController` `processPegOut`). `processPegOut`
(`BridgeService.java:367-503`) needs, in order: the confirmed anchor carrying
the burn, `findVault(chainId, burn.getVaultRef())`, and the still-locked
backstop (`BridgeService.java:442-448`). After an L0 reset all three fail —
vault unknown, 0 locked, old peg-in outpoint unspendable — so a burn of
**pre-reset** wrapped tokens is retried forever and never releases.

Direction asymmetry:

- L0→L1 after reset: works automatically (new locks → new mints).
- L1→L0 of pre-reset mints: impossible. Those balances persist on the kept
  chain but are stranded (burnable, unreleasable).
- L1→L0 of post-reset mints: works normally once anchors flow again.

Migration for stranded holders (amounts recoverable, accounting link new):

1. holder burns old-wrapped on SOCIAL;
2. operator (or holder) locks equivalent fresh L0 funds to the vault —
   if the same `GENESIS_CSV` re-minted the operator wallet, re-lock the same
   sums;
3. the watcher auto-mints new wrapped 1:1 to the beneficiary.

Re-importing old vault/anchor records into the fresh L0 does NOT work: the
release input references old-chain blocks that don't exist there
(`BridgeService.java:453-476`).

---

## 6. Genesis payout ceremony (fresh L0 has no funded wallets)

A key-mode reset mints the whole supply to the ceremony 2-of-3 multisig —
no validator, operator, vault or user wallet holds a sat until the payout
runs. Order: reset → payout → stake/vault/peg-in. Tool:
`helper/prod/genesis-payout.sh` (drives
`helper/prod/validators/GenesisPayoutTool.java`); verifier:
`helper/prod/initprodtest.sh check` (offline, run before every ceremony). Keys:
`/home/jcui/validators/genesis-{0,1,2}.env` (seeds, chmod 600, never commit),
public record `genesis-2of3.env` (pubkeys + `EXPECTED_GENESIS`).

- [ ] `initprodtest.sh check` green (12/12) — keys, genesis record,
      dry-run crypto proof, guards. Do not start the ceremony red.
- [ ] Build from the deployed commit first (`mvn -q package -DskipTests`):
      the tool recomputes genesis locally, so its jar must match the
      deployed image — a stale jar fails safe (preflight aborts on hash
      mismatch, bad spends never confirm) but wastes the ceremony window.
- [ ] `genesis-payout.sh genesis` — offline record. Compare
      `EXPECTED_GENESIS` with `genesis-2of3.env` and with the node's block
      (fetch `<L0>/getBlockByHash`). Mismatch = wrong build or wrong
      `genesisPub` order — stop.
- [ ] Stage the distribution CSV (`GENESIS_CSV`, default the prodsim file —
      for prod pass the new file). Addresses listed in `GenesisOutputExclude.csv`
      (default: next to the CSV) are skipped from pay, sum, batches and verify.
      Hard rule, enforced by preflight:
      **CSV sum + batch fees must fit `BigtangleCoinTotal`** (1e17 sat).
      The prodsim snapshot sums above it and can never be paid from this
      genesis — trim the prod CSV first.
- [ ] `genesis-payout.sh preflight` — read-only gates, all must pass:
      node genesis hash/script/value match, sum+fees fit, L0 finality at/above
      the coinbase-maturity gate (`MATURITY_DEPTH`, default 100 =
      `spendableCoinbaseDepth` — this also proves chain liveness before any
      funds move), already-funded report.
- [ ] `genesis-payout.sh payout` — pays in batches (`BATCH_ROWS`, default
      200/tx), each waiting for its block before the next is built; every
      batch txHash is printed — **keep the console log** (it is the recovery
      record alongside the progress file). Interrupt = safe stall: resume by
      re-running (progress file), by `resume <batchTxHash>` → `CHANGE_OUTPOINT`,
      or explicit `CHANGE_OUTPOINT=block:tx:index:value`. An invalid tx never
      confirms; funds stay put.
- [ ] `genesis-payout.sh verify` — exit 0 iff every CSV row holds >= its
      value (confirmed BIG). Only then stake validators, fund the vault,
      peg-in L1. (`initprodtest.sh e2e --yes` runs preflight→payout→verify
      as one logged strict pass.)
- [ ] Destroy the genesis seeds after a green verify (any leftover change
      was already swept into later batches — the final batch carries none).
      Until destruction, the 2 seed files in use control the remaining supply.

Do NOT rotate `SOCIAL_CONSUMERIDSUFFIX`, do NOT wipe `social` DBs (see §3),
and do NOT run payout against a chain whose genesis you have not verified —
`preflight` exists so a wrong-chain payout is impossible, not unlikely.

---

## 5. Post-reset verification checklist

- [ ] `prod.sh status`: all L0 nodes running, API + HTTPS up.
- [ ] L0 finality advancing (`getChainNumber` finalized length growing past 0).
- [ ] Genesis ceremony (§6) complete: preflight clean, payout complete,
      verify exit 0, seeds destroyed.
- [ ] `prod-social.sh status`: both L1 nodes still running, API + HTTPS up,
      no restart loop.
- [ ] L1 logs: `Peg-in polling failed` / `Anchor watcher failed` warnings
      stop after L0 is back; no `duplicate kafka consumerIdSuffix` errors.
- [ ] L0 validators re-staked; L0 duties attesting.
- [ ] Test peg-in on fresh L0 (small lock → wrapped mint on L1) before
      announcing the bridge back.
- [ ] Stranded-supply decision recorded: accept (test funds) or migrate
      per §4 with amounts reconciled against pre-reset vault records.
