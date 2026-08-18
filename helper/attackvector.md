Copyright 2018 Inasset GmbH.

# Layer0 Attack Vectors & Mitigations

## Network Layer

### 1. Eavesdropping / Man-in-the-Middle

Plain HTTP is the default — SSL is disabled (`SSL=false`). Gossip, private keys,
transactions, and auth tokens travel in cleartext.

**Mitigation:**
- Set `SSL=true` and mount a PKCS12 keystore via `KEYSTORE`.
- Terminate TLS at a reverse proxy (nginx, Cloudflare Tunnel) in front of
  `layer0-server`.

### 2. No Endpoint Authentication (default)

`permissioned=false` by default — all 55 REST endpoints accept requests from
anyone with network access.

**Mitigation:**
- Set `permissioned=true` in production. Clients must provide an
  `accessToken` header with `pubkey,signature,sessionRandom` verified via
  ECDSA challenge.
- Use a firewall/VPC to restrict access to trusted IPs.

### 3. Private Key Exfiltration via setValidatorKey

The `setValidatorKey` endpoint accepts a hex-encoded private key over HTTP.
Combined with no SSL and no auth by default, an attacker can steal validator
keys.

**Mitigation:**
- Never expose this endpoint to the public internet.
- Enable SSL+permissioned mode before using it.
- Restrict to internal network only.

### 4. Minimal Rate Limiting

`ipcheck=false` by default. Even when enabled, `ipCheck()` only protects 4 of
55 endpoints (`getTip`, `getOutputs`, `getBalances`, `getAccountBalances`).
51 endpoints have no rate limiting at all.

**Mitigation:**
- Set `ipcheck=true`.
- Deploy a WAF or API gateway (Cloudflare, AWS WAF, nginx rate-limit) in
  front of the server.
- Tune `userDataService` thresholds for your traffic profile.

### 5. Prometheus Actuators Unauthenticated

`management.endpoints.web.exposure.include: '*'` exposes all actuator endpoints
with no authentication, leaking heap dumps, thread dumps, environment variables,
and configuration.

**Mitigation:**

```yaml
management.endpoints.web.exposure.include: health,info
management.endpoint.env.enabled: false
```

Or firewall actuator routes.

### 6. Session Auth Cached Indefinitely

Once `checkAuth()` passes ECDSA verification, the result is cached in the HTTP
session without expiry. A stolen session cookie grants permanent access.

**Mitigation:**
- Invalidate sessions after a timeout.
- Rotate `sessionRandomNum` periodically.

## Consensus Layer

### 7. Double-Spend via Mempool

`submitTransaction` accepts transactions into the mempool with no signature
validation or double-spend check at ingress. Validation happens later in the
consensus/confirmation pipeline.

**Mitigation:**
- Already handled downstream by `ServiceBaseConfirmation` conflict detection
  and `resolveConflicts()`. The DAG-based consensus resolves double-spends
  at chainlength depth, not at mempool. No action needed.

### 8. Batch Blocks Skip Re-Verification

`saveBatchBlock()` skips transaction re-verification and solidity checks.
A compromised peer can inject invalid blocks via batch sync.

**Mitigation:**
- Batch blocks come from trusted peers only. Ensure peer identity via
  permissioned mode.
- Verify the sync source — only sync from known, permissioned peers.

### 9. Beacon Header Validation Gap

`connectRewardBlock` for beacon blocks does not validate slot number,
proposer index, or RANDAO reveal (documented in blockchain.md).

**Mitigation:**
- This is a known simplification for testnet. Before mainnet, implement
  full beacon header validation per the Ethereum beacon chain spec.

### 10. Single-Key Vault (Peg-Out)

Bridge peg-out currently uses a single private key (`vaultPriKeyHex`) per
TODO.md Phase 4. A single key compromise loses all bridged funds.

**Mitigation:**
- M-of-N multisig vault is planned (TODO Phase 4).
- Until then, keep the vault key in a hardware security module (HSM) and
  never expose it to the application.

## Crypto Layer

### 11. FAKE_SIGNATURES Flag

`ECKey.FAKE_SIGNATURES` bypasses all signature verification when `true`.
Present in production code marked `@VisibleForTesting`.

**Mitigation:**
- Ensure this flag is never set in production. A build-time check or
  `-DfakeSignatures=false` enforcement in the startup script.

### 12. Post-Quantum Crypto Activation

PQ signatures (ML-DSA-87 + SLH-DSA-SHA2-256s) are implemented but governed.
Until `NetworkParameters` activates `SIGNATURE_V2`/`SIGNATURE_V3`, only ECDSA
is enforced.

**Mitigation:**
- Activate PQ signatures via governance once the network is ready.
- Monitor NIST for algorithm deprecation — the governance path allows
  upgrading broken algorithms.

### 13. Broken DomainValidator.isValidTld()

`isValidTld()` always returns `true` (TLD list is defined but the check is
commented out). Domain-name token creation accepts any TLD, even nonexistent
ones.

**Mitigation:**
- Fix `DomainValidator.isValidTld()` to use the defined TLD lists instead
  of `return true`.

## Infrastructure

### 14. Default Database Credentials

`application.yml` ships with `username: root, password: test1234`. Deployments
that forget to override this expose their database.

**Mitigation:**
- Always override `DB_USERNAME` and `DB_PASSWORD` in production.
- Use environment variables, not the default config.

### 15. Default Keystore Password

`KEYSTOREPW` defaults to `changeit`. An attacker with filesystem access can
read the keystore.

**Mitigation:**
- Generate a unique PKCS12 keystore and set a strong password via
  `KEYSTOREPW` environment variable.

### 16. Gossip Uses Plain HTTP

`GossipService` uses `"http://" + p + "/" + path` for inter-node gossip.
Peer-to-peer traffic is unencrypted.

**Mitigation:**
- Run all nodes with SSL enabled so gossip ports are HTTPS-served.
- Use a VPN or wireguard mesh between nodes.

### 17. No Input Validation on L0AnchorHandler

`L0AnchorHandler.checkFull()` and `checkFormal()` both return success with
no validation. Anchor blocks bypass all checks.

**Mitigation:**
- Implement proper block validation in both methods before mainnet.

## Summary Table

| # | Attack Vector | Severity | Mitigation |
|---|--------------|----------|------------|
| 1 | Plain HTTP / MITM | High | Enable SSL, reverse proxy |
| 2 | No endpoint auth | High | Set `permissioned=true` |
| 3 | setValidatorKey leaks key | Critical | Firewall, auth, never public |
| 4 | No rate limiting | Medium | `ipcheck=true`, WAF |
| 5 | Prometheus open | Medium | Lockdown actuator routes |
| 6 | Session auth cached | Low | Add session expiry |
| 7 | Mempool no validation | Low | Handled by DAG consensus |
| 8 | Batch blocks skip checks | Medium | Trusted peers only |
| 9 | Beacon header gap | Medium | Implement full validation |
| 10 | Single-key vault | High | M-of-N multisig (planned) |
| 11 | FAKE_SIGNATURES flag | Critical | Production check |
| 12 | PQ not yet active | Low | Activate via governance |
| 13 | Broken TLD validation | Low | Fix `isValidTld()` |
| 14 | Default DB creds | High | Override in production |
| 15 | Default keystore pw | Medium | Generate + set strong pw |
| 16 | Gossip plain HTTP | High | VPN mesh between nodes |
| 17 | L0Anchor no validation | High | Implement block checks |
