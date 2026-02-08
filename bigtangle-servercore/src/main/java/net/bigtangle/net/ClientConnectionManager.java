/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

package net.bigtangle.net;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.Service;

import java.net.SocketAddress;

/**
 * <p>A generic interface for an object which keeps track of a set of open client connections, creates new ones and
 * ensures they are serviced properly.</p>
 *
 * <p>When the service is {@link com.google.common.util.concurrent.Service#stop()}ed, all connections will be closed and
 * the appropriate connectionClosed() calls must be made.</p>
 */
public interface ClientConnectionManager extends Service {
    /**
     * Creates a new connection to the given address, with the given connection used to handle incoming data. Any errors
     * that occur during connection will be returned in the given future, including errors that can occur immediately.
     */
    ListenableFuture<SocketAddress> openConnection(SocketAddress serverAddress, StreamConnection connection);

    /** Gets the number of connected peers */
    int getConnectedClientCount();

    /** Closes n peer connections */
    void closeConnections(int n);
}
