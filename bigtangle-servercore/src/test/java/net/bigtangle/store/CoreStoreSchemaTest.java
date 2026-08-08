package net.bigtangle.store;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.bigtangle.params.TestParams;

/**
 * Verifies the concrete layer store classes produce the right DDL: the CORE
 * store used by Layer 0 must not create contract/EVM tables, while the CONTRACT
 * store must.
 */
public class CoreStoreSchemaTest {

    private static final class Probe extends PostgreSQLFullBlockStore {
        Probe(BlockStoreInterface.StoreDomain domain) {
            super(TestParams.get(), null);
            setStoreDomain(domain);
        }
        List<String> ddl() { return getCreateTablesSQL(); }
    }

    private String coreDdl() {
        return String.join("\n", new Probe(BlockStoreInterface.StoreDomain.CORE).ddl());
    }
    private String contractDdl() {
        return String.join("\n", new Probe(BlockStoreInterface.StoreDomain.CONTRACT).ddl());
    }
    private String orderDdl() {
        return String.join("\n", new Probe(BlockStoreInterface.StoreDomain.ORDER).ddl());
    }

    @Test
    public void coreStoreExcludesContractTables() {
        String ddl = coreDdl();
        assertFalse(ddl.contains("contractevent"), "L0 core store must not create contractevent");
        assertFalse(ddl.contains("contractresult"), "L0 core store must not create contractresult");
        assertFalse(ddl.contains("evm_receipt"), "L0 core store must not create evm_receipt");
        // order tables are kept for the shared reward pipeline
        assertTrue(ddl.contains("CREATE TABLE orders"), "L0 keeps orders for the reward pipeline");
        assertTrue(ddl.contains("CREATE TABLE matching"), "L0 keeps matching for the reward pipeline");
    }

    @Test
    public void contractStoreIncludesContractTables() {
        String ddl = contractDdl();
        assertTrue(ddl.contains("CREATE TABLE contractevent"), "contract store must create contractevent");
        assertTrue(ddl.contains("CREATE TABLE contractresult"), "contract store must create contractresult");
        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS evm_receipt"), "contract store must create evm_receipt");
    }

    @Test
    public void orderStoreIncludesOrderTablesExcludesContractTables() {
        String ddl = orderDdl();
        assertTrue(ddl.contains("CREATE TABLE orders"), "order store must create orders");
        assertFalse(ddl.contains("contractevent"), "order store must not create contractevent");
        assertFalse(ddl.contains("evm_receipt"), "order store must not create evm_receipt");
    }
}
