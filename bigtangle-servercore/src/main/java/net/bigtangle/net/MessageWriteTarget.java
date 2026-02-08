/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

package net.bigtangle.net;

import java.io.IOException;

/**
 * A target to which messages can be written/connection can be closed
 */
public interface MessageWriteTarget {
    /**
     * Writes the given bytes to the remote server.
     */
    void writeBytes(byte[] message) throws IOException;
    /**
     * Closes the connection to the server, triggering the {@link StreamConnection#connectionClosed()}
     * event on the network-handling thread where all callbacks occur.
     */
    void closeConnection();
}
