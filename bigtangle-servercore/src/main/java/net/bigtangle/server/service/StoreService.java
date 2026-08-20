package net.bigtangle.server.service;

import java.sql.SQLException;
import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.store.BlockStoreInterface;
import net.bigtangle.store.BlockStoreInterface.StoreDomain;
import net.bigtangle.store.DatabaseFullBlockStoreBase;
import net.bigtangle.store.PostgreSQLFullBlockStore;

@Service
public class StoreService {

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("dataSource")
    protected DataSource dataSource;

    /**
     * Dedicated pool for the consensus path (slot tick / beacon proposal /
     * block connection / confirmation). Threads that run consensus duty set
     * this thread-local context so every {@link #getStore()} call on that
     * thread draws a connection from the dedicated pool instead of the
     * submit/API pool, which can be exhausted under load.
     */
    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("posDataSource")
    protected DataSource posDataSource;

    private static final ThreadLocal<Boolean> POS_CONTEXT = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** Marks the current thread as a consensus-duty thread. */
    public static void enterPosContext() {
        POS_CONTEXT.set(Boolean.TRUE);
    }

    /** Clears the consensus-duty marker for the current thread. */
    public static void exitPosContext() {
        POS_CONTEXT.set(Boolean.FALSE);
    }

    @Autowired
    protected NetworkParameters networkParameters;

    /** Layer domain this node is provisioned for (core / order / contract / all). */
    @Value("${store.domain:all}")
    private String storeDomain;

    public BlockStoreInterface getStore() throws BlockStoreException {
        try {
            StoreDomain domain = parseDomain();
            DataSource source = POS_CONTEXT.get() ? posDataSource : dataSource;
            java.sql.Connection c = source.getConnection();
            // A pooled connection can be returned mid-transaction if a batch
            // write was not properly finalized (autocommit=false + open
            // transaction). Queries on such a connection then see a STALE
            // snapshot — e.g. getStakeDeposit returns null for a record that
            // was committed after the stale transaction began, which in
            // multi-node operation makes validators reject their own
            // attestations. Reset the connection to a clean state so every
            // store starts from the latest committed view.
            if (!c.getAutoCommit()) {
                c.rollback();
                c.setAutoCommit(true);
            }
            BlockStoreInterface store = new PostgreSQLFullBlockStore(networkParameters, c);
            if (domain != StoreDomain.ALL && store instanceof DatabaseFullBlockStoreBase base) {
                base.setStoreDomain(domain);
            }
            return store;
        } catch (SQLException e) {
            throw new BlockStoreException(e);
        }
    }

    private StoreDomain parseDomain() {
        if (storeDomain == null) {
            return StoreDomain.ALL;
        }
        try {
            return StoreDomain.valueOf(storeDomain.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return StoreDomain.ALL;
        }
    }
}
