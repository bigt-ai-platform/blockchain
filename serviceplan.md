# Service Plan - Simplify Service Methods

## Architecture Dependencies

```
bigtangle-servercore (base layer)
       |                |
       v                v
bigtangle-server    bigtangle-order
                        |
                        v
                  bigtangle-mcmc
```

- **server** → uses **servercore**
- **order** → based on **servercore**
- **mcmc** → uses **order**

---

## bigtangle-servercore (Base Services)

All other modules depend on these services.

### BlockService
| Method | Description |
|--------|-------------|
| `getBlock(hash, store)` | Get block by hash |
| `getBlockWrap(hash, store)` | Get block wrap by hash |
| `searchBlock(request, store)` | Search blocks by criteria |
| `searchBlockByBlockHashs(request, store)` | Search blocks by hash list |
| `batchBlock(block, store)` | Batch insert block |
| `blocksFromChainLength(start, end, store)` | Get blocks in chain range |
| `blocksFromNonChainHeigth(cutoffHeight, store)` | Get non-chain blocks |
| `addConnected(bytes, allowUnsolid)` | Add connected block from bytes |
| `addConnectedBlock(block, allowUnsolid)` | Add connected block |
| `checkBlockBeforeSave(block, store)` | Validate block before save |
| `getMaxConfirmedReward(store)` | Get max confirmed reward |
| `getAllConfirmedReward(store)` | Get all confirmed rewards |
| `findRetryBlocks(request, store)` | Find blocks to retry |

### BlockSaveService
| Method | Description |
|--------|-------------|
| `saveBlock(block, store)` | Save block to store |
| `broadcastBlock(block)` | Broadcast block to network |

### OutputService
| Method | Description |
|--------|-------------|
| `getAccountBalanceInfo(pubKeyHashs, store)` | Get account balance |
| `getAccountBalanceInfoFromAccount(pubKeyHashs, store)` | Get balance from account |
| `filterToken(outputs)` | Filter token outputs |
| `calculateAllSpendCandidates(pubKeyHashs, store)` | Get spendable outputs |
| `getOpenTransactionOutputs(address, store)` | Get open UTXOs by address |
| `getOpenAllOutputs(tokenid, store)` | Get all open outputs for token |
| `getAccountOutputs(pubKeyHashs, store)` | Get account outputs |
| `getOutputsHistory(from, to, start, end, ...)` | Get output history |
| `getOutputsWithHexStr(hexStr, store)` | Get outputs by hex |
| `checkValidAddress(address)` | Validate address |

### TokensService
| Method | Description |
|--------|-------------|
| `getTokenById(tokenid, store)` | Get token by ID |
| `getToken(blockhash, store)` | Get token by block hash |
| `getWebTokensList(store)` | List web tokens |
| `getContractTokensList(store)` | List contract tokens |
| `searchTokens(name, store)` | Search tokens by name |
| `searchExchangeTokens(name, store)` | Search exchange tokens |

### TokenDomainnameService
| Method | Description |
|--------|-------------|
| `queryDomainnameTokenPermissionedAddresses(hash, store)` | Query domain permissions |
| `queryDomainnameTokenMultiSignAddresses(hash, store)` | Query domain multisig addresses |
| `queryParentDomainnameBlockHash(domainname, store)` | Get parent domain hash |
| `queryDomainnameBlockHash(domainname, store)` | Get domain block hash |

### PayMultiSignService
| Method | Description |
|--------|-------------|
| `getPayMultiSignDetails(orderid, store)` | Get multisign payment details |
| `launchPayMultiSign(data, store)` | Launch multisign payment |
| `payMultiSign(request, store)` | Execute multisign payment |
| `getPayMultiSignList(pubKeys, store)` | List multisign payments |
| `getPayMultiSignAddressList(orderid, store)` | List addresses for order |

### OrderTickerService
| Method | Description |
|--------|-------------|
| `getLastMatchingEvents(tokenIds, basetoken, store)` | Get latest order matches |
| `getTimeBetweenMatchingEvents(tokenids, basetoken, start, end, store)` | Get matches in time range |
| `getTimeAVBGBetweenMatchingEvents(tokenids, basetoken, start, end, store)` | Get avg matches in range |

### OrderdataService
| Method | Description |
|--------|-------------|
| `getOrderdataList(address, addresses, tokenid, store)` | Get order data list |

### SyncBlockService
| Method | Description |
|--------|-------------|
| `startSingleProcess()` | Start sync process |
| `startInit()` | Initialize sync |
| `syncChain(chainlength, initsync, store)` | Sync chain blocks |
| `syncNonChained(store)` | Sync non-chain blocks |
| `requestBlock(hash, store)` | Request single block |
| `requestBlocks(start, end, server, store)` | Request block range |
| `getMaxConfirmedReward(server)` | Get remote max reward |
| `connectingOrphans(store)` | Connect orphan blocks |

### CacheBlockService
| Method | Description |
|--------|-------------|
| `getBlock(hash, store)` | Get cached block |
| `cachePutBlock(block, store)` | Put block in cache |
| `evictBlock(block, store)` | Evict block from cache |
| `getMaxConfirmedReward(store)` | Get cached max reward |
| `getAccountBalance(address, store)` | Get cached balance |
| `getOpenTransactionOutputs(address, store)` | Get cached outputs |

### AccessPermissionedService
| Method | Description |
|--------|-------------|
| `getSessionRandomNumResp(pubKey, store)` | Get session auth token |
| `checkSessionRandomNumResp(pubKey, accessToken, store)` | Verify session token |

### AccessGrantService
| Method | Description |
|--------|-------------|
| `addAccessGrant(pubKey, store)` | Grant access |
| `deleteAccessGrant(pubKey, store)` | Revoke access |
| `getCountAccessGrantByAddress(address, store)` | Count grants for address |

### SubtanglePermissionService
| Method | Description |
|--------|-------------|
| `savePubkey(pubkey, signHex, store)` | Save subtangle pubkey |
| `updateSubtanglePermission(pubkey, signHex, userdataPubkey, status, store)` | Update permission |
| `getSubtanglePermissionList(pubkeys, store)` | Get permission list |
| `getAllSubtanglePermissionList(store)` | Get all permissions |

### UserDataService
| Method | Description |
|--------|-------------|
| `getUserData(dataclassname, pubKey, store)` | Get user data |
| `getUserDataList(blocktype, pubKeyList, store)` | List user data |
| `ipCheck(reqCmd, contentBytes, request)` | IP-based access check |
| `addStatistcs(reqCmd, remoteAddr)` | Add request statistics |

### CacheBlockPrototypeService
| Method | Description |
|--------|-------------|
| `getBlockPrototype(store)` | Get cached block prototype |

### HeathCheckService
| Method | Description |
|--------|-------------|
| `startSingleProcess()` | Run health check |

### MissingNumberCheckService
| Method | Description |
|--------|-------------|
| `check(sequence)` | Check for missing numbers in reward sequence |

---

## bigtangle-server (uses servercore)

Scheduled tasks and orchestration layer on top of servercore.

### ScheduleSyncBlockService
| Method | Description |
|--------|-------------|
| `syncService()` | Scheduled block sync (delegates to servercore SyncBlockService) |

### ScheduleInitService
| Method | Description |
|--------|-------------|
| `syncService()` | Scheduled init sync |

### ScheduleHealthCheckService
| Method | Description |
|--------|-------------|
| `checkService()` | Scheduled health check |

### BlockBatchService
| Method | Description |
|--------|-------------|
| `batch()` | Batch process blocks |
| `startSingleProcess()` | Start batch process |

### ScheduleAVGPriceService
| Method | Description |
|--------|-------------|
| `updatemcmcService()` | Scheduled MCMC avg price update |

### ScheduleProtectService
| Method | Description |
|--------|-------------|
| `protect()` | Scheduled protection task |

### UpdateChainService
| Method | Description |
|--------|-------------|
| `updateChain()` | Scheduled chain update |

---

## bigtangle-order (based on servercore)

Order matching, contract execution, and reward calculation.

### OrderExecutionService
| Method | Description |
|--------|-------------|
| `startSingleProcess()` | Start order execution process |
| `createOrderExecution(store)` | Create order execution block |
| `createOrderExecutionDo(store)` | Execute order matching logic |
| `createOrderExecution(block, store)` | Create execution from block |

### ContractExecutionService
| Method | Description |
|--------|-------------|
| `startSingleProcess()` | Start contract execution process |
| `createContractExecution(store)` | Create contract execution |
| `createContractExecutionSave(store)` | Save contract execution |
| `getOpenContract(store)` | Get open contracts |
| `createContractExecution(block, contract, store)` | Execute contract on block |
| `createContractExecutionDo(block, contract, store)` | Execute contract logic |

### BlockServiceCreate
| Method | Description |
|--------|-------------|
| `adjustHeightRequiredBlocks(block, store)` | Adjust block height |
| `adjustPrototype(block, store)` | Adjust block prototype |
| `calcHeightRequiredBlocks(block, store)` | Calculate required height |

### MultiSignServiceCreate
| Method | Description |
|--------|-------------|
| `saveMultiSign(block, store)` | Save multisign block |
| `deleteMultiSign(block, store)` | Delete multisign block |
| `signTokenAndSaveBlock(block, store)` | Sign token and save |

### ServiceBaseReward
| Method | Description |
|--------|-------------|
| `calcRewardInfo(contractExecute, prevTrunk, prevBranch, ...)` | Calculate reward info |

### ScheduleOrdermatchService
| Method | Description |
|--------|-------------|
| `orderExecutionService()` | Scheduled order matching |

### ScheduleContractService
| Method | Description |
|--------|-------------|
| `contractExecutionService()` | Scheduled contract execution |

---

## bigtangle-mcmc (uses order)

MCMC consensus, tips selection, and reward creation.

### MCMCService
| Method | Description |
|--------|-------------|
| `startSingleProcess()` | Start MCMC process |
| `startSingleProcessDo()` | Execute MCMC logic |
| `update(store)` | Update MCMC state |
| `calcNewBlockPrototype(store)` | Calculate new block prototype |

### TipsService
| Method | Description |
|--------|-------------|
| `getRatingTips(maxReward, count, maxHeight, ...)` | Get rated tips for selection |
| `getValidatedBlockPair(store)` | Get validated block pair for tips |
| `getValidatedBlockPair(maxReward, ...)` | Get validated pair with params |
| `performTransition(currentBlock, candidates, store)` | MCMC random walk transition |

### RewardService
| Method | Description |
|--------|-------------|
| `startSingleProcess()` | Start reward process |
| `createReward(store)` | Create reward block |
| `createReward(prevRewardHash, store)` | Create reward from prev hash |
| `createReward(prevRewardHash, prevTrunk, prevBranch, ...)` | Create reward with tips |
| `createMiningRewardBlock(prevRewardHash, prevTrunk, prevBranch, ...)` | Create mining reward |
| `getMaxConfirmedReward(store)` | Get max confirmed reward |
| `getAllConfirmedReward(store)` | Get all confirmed rewards |
| `calculateNextBlockDifficulty(currRewardInfo)` | Calculate next difficulty |

### ScheduleInitService
| Method | Description |
|--------|-------------|
| `syncService()` | Scheduled init sync for MCMC |

### ScheduleMCMCService
| Method | Description |
|--------|-------------|
| `updatemcmcService()` | Scheduled MCMC update |

### ScheduleRewardService
| Method | Description |
|--------|-------------|
| `updateReward()` | Scheduled reward creation |

### UpdateChainService
| Method | Description |
|--------|-------------|
| `updateChain()` | Scheduled chain update for MCMC |

---

## Simplification Recommendations

### 1. Eliminate Duplicated Services
- `UpdateChainService` exists in both **server** and **mcmc** — consolidate into **servercore** or share via **order**.
- `ScheduleInitService` exists in both **server** and **mcmc** — extract common logic to **servercore**.

### 2. Server Module (uses servercore only)
- All `Schedule*Service` classes should only orchestrate timing; actual logic stays in **servercore**.
- Remove any direct DB/store logic; delegate entirely to servercore services.

### 3. Order Module (based on servercore)
- `OrderExecutionService` and `ContractExecutionService` are the core business logic.
- `BlockServiceCreate` and `MultiSignServiceCreate` extend servercore block/multisign functionality.
- Keep order-specific scheduling (`ScheduleOrdermatchService`, `ScheduleContractService`) here.

### 4. MCMC Module (uses order)
- `MCMCService`, `TipsService`, `RewardService` are MCMC-specific consensus services.
- Should depend on **order** for `ServiceBaseReward.calcRewardInfo()` and order execution.
- Should depend on **servercore** (transitively via order) for `BlockService`, `CacheBlockService`, etc.

### 5. Common Interface Pattern
All services use `BlockStoreInterface store` parameter — consider:
- Making store injection automatic via Spring context instead of passing as parameter.
- Reducing method overloads by using builder/config objects.

### 6. Cache Layer
- `CacheBlockService` in servercore wraps most read operations — all modules should use this as the read path.
- Avoid direct store reads in server/order/mcmc when cached versions exist.
