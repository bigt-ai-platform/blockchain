package net.bigtangle.store;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.bigtangle.core.StoreDomain;
import net.bigtangle.params.TestParams;

/**
 * Verifies the per-layer store split: a Layer-0 (CORE) store must not create
 * order-matching / contract tables, an ORDER store must not create contract
 * tables, and a CONTRACT store must not create order tables.
 *
 * <p>Asserts on the DDL each domain store produces (the mechanism that stops
 * Layer 0 from provisioning order/contract tables). Runs without a database.
 */
public class StoreDomainTest {

    private static final String ORDER_TABLE = "CREATE TABLE orders";
    private static final String ORDER_CANCEL_TABLE = "CREATE TABLE ordercancel";
    private static final String MATCHING_TABLE = "CREATE TABLE matching";
    private static final String ORDER_RESULT_TABLE = "CREATE TABLE orderresult";
    private static final String CONTRACT_EVENT_TABLE = "CREATE TABLE contractevent";
    private static final String CONTRACT_RESULT_TABLE = "CREATE TABLE contractresult";
    private static final String EVM_RECEIPT_TABLE = "CREATE TABLE IF NOT EXISTS evm_receipt";
    private static final String CORE_BLOCKS_TABLE = "CREATE TABLE blocks";
    private static final String CORE_OUTPUTS_TABLE = "CREATE TABLE outputs";

    /** A Postgres store subclass exposing the protected DDL for testing. */
    private static final class DdlProbe extends PostgreSQLFullBlockStore {
        DdlProbe(Connection conn, StoreDomain domain) {
            super(TestParams.get(), conn);
            setStoreDomain(domain);
        }

        List<String> ddl() {
            return getCreateTablesSQL();
        }
    }

    private String allDdl(StoreDomain domain) {
        return String.join("\n", new DdlProbe(null, domain).ddl());
    }

    @Test
    public void testCoreStoreHasNoContractTables() {
        String all = allDdl(StoreDomain.CORE);
        assertTrue(all.contains(CORE_BLOCKS_TABLE), "core store must create blocks");
        assertTrue(all.contains(CORE_OUTPUTS_TABLE), "core store must create outputs");
        // The reward pipeline (epoch rewards) reads the order book on every
        // layer, so order tables are always present — even on Layer 0.
        assertTrue(all.contains(ORDER_TABLE), "core store keeps order tables for the reward pipeline");
        assertFalse(all.contains(CONTRACT_EVENT_TABLE), "core store must not create contractevent");
        assertFalse(all.contains(CONTRACT_RESULT_TABLE), "core store must not create contractresult");
        assertFalse(all.contains(EVM_RECEIPT_TABLE), "core store must not create evm_receipt");
    }

    @Test
    public void testOrderStoreHasNoContractTables() {
        String all = allDdl(StoreDomain.ORDER);
        assertTrue(all.contains(ORDER_TABLE), "order store must create orders");
        assertTrue(all.contains(MATCHING_TABLE), "order store must create matching");
        assertFalse(all.contains(CONTRACT_EVENT_TABLE), "order store must not create contractevent");
        assertFalse(all.contains(EVM_RECEIPT_TABLE), "order store must not create evm_receipt");
    }

    @Test
    public void testContractStoreHasOrderTablesAndNoContractGatingOnOrder() {
        String all = allDdl(StoreDomain.CONTRACT);
        assertTrue(all.contains(CONTRACT_EVENT_TABLE), "contract store must create contractevent");
        assertTrue(all.contains(CONTRACT_RESULT_TABLE), "contract store must create contractresult");
        assertTrue(all.contains(EVM_RECEIPT_TABLE), "contract store must create evm_receipt");
        assertTrue(all.contains(ORDER_TABLE), "contract store keeps order tables for the reward pipeline");
        assertTrue(all.contains(MATCHING_TABLE), "contract store keeps matching for the reward pipeline");
    }

    @Test
    public void testAllStoreHasEverything() {
        String all = allDdl(StoreDomain.ALL);
        assertTrue(all.contains(ORDER_TABLE), "ALL store must create orders");
        assertTrue(all.contains(CONTRACT_EVENT_TABLE), "ALL store must create contractevent");
        assertTrue(all.contains(CORE_BLOCKS_TABLE), "ALL store must create blocks");
    }
}
