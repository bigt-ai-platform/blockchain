# MCMC Optional (Runtime Consensus Toggle) — Plan

Goal: make the MCMC tip-selection / tip-queue consensus path a *runtime option*.
When `service.schedule.mcmc` (`SERVICE_MCMC`) is `false`, a node runs **pure PoS**
— slot tick, beacon production, attestation and reward-chain confirmation all
continue, but the MCMC scheduler, weight/depth/rating computation and
TipsQueue generation are fully skipped, and block construction falls back to
GHOST tip selection instead of MCMC tips.

Status: **Draft — not yet implemented.**

## Problem

`service.schedule.mcmc` already exists, but today it is an all-or-nothing
"consensus active" switch, not an MCMC switch:

1. The property is bound to `ScheduleConfiguration.chainlength_active`, which is
   ALSO the gate for pure-PoS / general services: `SlotTickService`,
   `UpdateChainService`, 4× `ScheduleSyncBlockService`, 4×
   `ScheduleHealthCheckService`, `ScheduleAVGPriceService`, the 4 bridge
   watchers and `AbstractScheduleInitService`. With `SERVICE_MCMC=false` the
   slot tick returns early, so **PoS never runs** — there is no pure-PoS mode.
2. MCMC tip selection is the only producer of `TipsQueue`
   (`MCMCService.calcNewBlockPrototype`). Consumers assume it exists:
   - `CacheBlockPrototypeService.getBlockPrototype` throws `NoBlockException`
     when the queue is empty (used by `getTip`, `batchBlocks`,
     `batchTransactionGroup`, `BlockServiceCreate.adjustPrototype`,
     `MultiSignServiceCreate.checkBlockPrototype`,
     `SlotService.proposeBeaconBlock`).
   - `BlockSaveService.batchTransactionGroup` (line 391) reads
     `store.getTipsQueue()` directly and returns 0 (drops the batch) when null.
   - Only `SlotService.proposeBeaconBlock` has a GHOST fallback
     (`ghostService.getTwoTips`) today.
3. The MCMC beans (`MCMCService`, `TipsService`, `ScheduleMCMCService`) are
   unconditional `@Component`/`@Service`s in the 4 modules that host MCMC code
   (`layer0-mcmc`, `l1-contract-server`, `l1-order-server`, `l1-pai-server`), so
   they are constructed even when disabled.

## Design

Decouple the two concerns: a **consensus** flag (drives PoS/general services,
default on) and an **MCMC** flag (drives only the MCMC consensus path).

### 1. Split the flags in `ScheduleConfiguration`

`bigtangle-servercore/.../server/config/ScheduleConfiguration.java`:

- Rename field/getter `chainlength_active` → `mcmcEnabled`
  (`isMcmcEnabled()`), keeping the property binding
  `@Value("${service.schedule.mcmc:false}")`.
- Add a new consensus switch:
  ```java
  @Value("${service.consensus.active:true}")
  boolean consensusActive;
  public boolean isConsensusActive() { return consensusActive; }
  ```
  Backward compatible: every current deployment that wants consensus sets
  `SERVICE_MCMC=true`; those nodes keep `service.schedule.mcmc=true` and behave
  identically (consensus default true). Nodes that previously set
  `SERVICE_MCMC=false` were inert; they now become pure-PoS nodes — the desired
  new behavior.

### 2. Re-point the non-MCMC gate sites → `isConsensusActive()`

Mechanical one-line swap in every production caller that is NOT MCMC:

- `bigtangle-servercore/.../service/schedule/AbstractScheduleInitService.java:36`
- `bigtangle-servercore/.../service/schedule/SlotTickService.java:60`
- `bigtangle-servercore/.../service/schedule/UpdateChainService.java:34`
- `layer0-server/.../service/schedule/ScheduleHealthCheckService.java:34`
- `layer0-server/.../service/schedule/ScheduleSyncBlockService.java:35`
- `l1-pai-server/.../service/schedule/ScheduleHealthCheckService.java:25`
- `l1-pai-server/.../service/schedule/ScheduleSyncBlockService.java:28`
- `l1-order-server/.../service/schedule/ScheduleHealthCheckService.java:34`
- `l1-order-server/.../service/schedule/ScheduleSyncBlockService.java:35`
- `l1-order-server/.../service/schedule/ScheduleAVGPriceService.java:64`
- `l1-contract-server/.../service/schedule/ScheduleHealthCheckService.java:34`
- `l1-contract-server/.../service/schedule/ScheduleSyncBlockService.java:35`
- `bigtangle-bridge/.../schedule/PegInWatcherService.java:49`
- `bigtangle-bridge/.../schedule/AnchorPostService.java:51`
- `bigtangle-bridge/.../schedule/PegOutRetryService.java:49`
- `bigtangle-bridge/.../schedule/AnchorWatcherService.java:79`

Keep `isMcmcEnabled()` only on the 4 `ScheduleMCMCService.updatemcmcService()`
copies (`layer0-mcmc`, `l1-contract-server`, `l1-order-server`,
`l1-pai-server`).

### 3. Skip the MCMC beans entirely when disabled

Add `@ConditionalOnProperty(name = "service.schedule.mcmc", havingValue = "true")`
to `MCMCService`, `TipsService` and `ScheduleMCMCService` in each of the 4
hosting modules (12 files). When disabled the beans are not created and the
`@Scheduled` task is not registered — "fully skipped, not just the scheduler".

- `ScheduleInitService` (mcmc packages) must stay unconditional: it performs the
  general `AbstractScheduleInitService` init (sets `serviceReady`), not MCMC
  work.
- Verified: no production code outside the `mcmc.service` packages autowires
  `MCMCService`/`TipsService`, so conditional beans won't break wiring. Test
  code (`layer0-mcmc`) runs with `SERVICE_MCMC=true` (default in
  `layer0-mcmc/application.yml`), so MCMC tests still get the beans.

### 4. GHOST fallback for tip-queue consumers

Make `CacheBlockPrototypeService.getBlockPrototype(store)` the single choke
point that falls back to pure-PoS tips:

```java
TipsQueue tipsQueue = store.getTipsQueue();
if (tipsQueue != null) {
    return networkParameters.getDefaultSerializer().makeBlock(tipsQueue.getBlock());
}
// Pure-PoS fallback: GHOST fork-choice tips (same shape as MCMC tips: trunk/branch).
List<Sha256Hash> tips = ghostService.getTwoTips(store);
Block trunk = store.get(tips.get(0));
Block branch = tips.size() > 1 ? store.get(tips.get(1)) : trunk;
if (trunk == null) throw new NoBlockException();
return Block.createBlock(networkParameters, trunk, branch);
```

Autowire `GhostService` via `@Lazy`/`ObjectProvider` (GhostService ↔ SlotService
↔ CacheBlockPrototypeService cycle). This automatically fixes `getTip`,
`batchBlocks`, `BlockServiceCreate.adjustPrototype`,
`MultiSignServiceCreate.checkBlockPrototype` and keeps
`SlotService.proposeBeaconBlock` working (its existing try/catch GHOST fallback
becomes redundant but harmless — optionally simplify later).

Then fix the direct consumer:
`BlockSaveService.batchTransactionGroup` (line 391) — replace the direct
`store.getTipsQueue()` read with `cacheBlockPrototypeService.getBlockPrototype(store)`
so batching keeps working when MCMC is off.

Decision point: fall back whenever the queue is empty (robust during MCMC
startup too), or only when `!isMcmcEnabled()`. Recommendation: fall back
whenever empty — simpler and safer; MCMC tips take priority when present.

### 5. Docs / config examples

- Add `service.consensus.active` (`SERVICE_CONSENSUS`) next to `mcmc` in each
  module's `application.yml` (server + mcmc modules).
- Update `docs/technical.md` and `blockchain*.md` port tables/settings
  descriptions; note in `helper/*` compose files / `validator_common.sh` that
  `--service.schedule.mcmc=true` keeps the MCMC+PoS mode and `false` selects
  pure PoS.

## Acceptance

1. `SERVICE_MCMC=false` (no MCMC beans): node initializes (`serviceReady`),
   syncs, `SlotTickService` runs, validators propose beacons and attest
   (GHOST fork choice), reward chain confirms, `getTip` and transaction batching
   work via GHOST tips. `MCMCService`/`TipsService`/`ScheduleMCMCService` absent
   from the context.
2. `SERVICE_MCMC=true`: byte-identical behavior to today (MCMC tip selection,
   weight/depth/rating, TipsQueue generation, MCMC scheduler).
3. Existing MCMC test suite green (`helper/testall.sh` runs with
   `SERVICE_MCMC=true`).
4. New test: boot a layer0-mcmc context with `SERVICE_MCMC=false`, assert the
   MCMC beans are absent, `getBlockPrototype` returns a GHOST-derived block, and
   `batchBlocksFromMempool` still batches.

## Risks / notes

- **Module copies:** the MCMC beans and schedule services are duplicated per
  module; edits must land in all 4 hosting modules or behavior diverges.
- **Bean-conditional blast radius:** grep confirmed no production autowires of
  `MCMCService`/`TipsService` outside `mcmc.service`; if one is missed the
  context fails to start — caught by `mvn -q compile` + `mvn test -pl layer0-mcmc`.
- **GHOST prototype shape:** the ghost-built prototype has no MCMC block
  evaluation; in pure-PoS that is expected (confirmation is reward-chain based,
  not weight/depth based — verified reward confirmation does not read MCMC
  weight/depth).
- **Split-node deployments:** server + mcmc modules sharing a DB must use the
  same `SERVICE_MCMC` value so both agree on the tip source (queue vs GHOST).