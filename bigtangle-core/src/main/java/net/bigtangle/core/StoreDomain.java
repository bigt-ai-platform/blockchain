package net.bigtangle.core;

/**
 * Which domain's tables a store is provisioned with. Layer-minimal nodes only
 * create their own domain's tables (plus the shared core tables); cross-domain
 * commands must be rejected at the API boundary.
 */
public enum StoreDomain {
    /** Layer 0 / core chain tables only (blocks, UTXO, token, stake, ...). */
    CORE,
    /** Core + order-matching tables (l1-order). */
    ORDER,
    /** Core + contract/EVM tables (l1-contract). */
    CONTRACT,
    /** All domains (legacy full store). */
    ALL;

    /**
     * Whether this store domain provides the tables a command requires.
     * Every store provides the core tables, so CORE requirements are always
     * satisfied; a specific non-core domain is only provided by itself or ALL.
     */
    public boolean satisfies(StoreDomain required) {
        if (required == null || required == CORE) {
            return true;
        }
        return this == ALL || this == required;
    }
}
