/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

package net.bigtangle.net;

import java.net.InetAddress;
import javax.annotation.Nullable;

/**
 * A factory which generates new {@link StreamConnection}s when a new connection is opened.
 */
public interface StreamConnectionFactory {
    /**
     * Returns a new handler or null to have the connection close.
     * @param inetAddress The client's (IP) address
     * @param port The remote port on the client side
     */
    @Nullable
    StreamConnection getNewConnection(InetAddress inetAddress, int port);
}
