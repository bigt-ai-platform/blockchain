/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

package net.bigtangle.pool.server;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;

import net.bigtangle.params.NetworkParameters;

/*
 * keep the potential list of servers and check the servers.
 * A List of server, which can provide block service
 */
public class ServerPool {

	private List<ServerState> servers = new ArrayList<ServerState>();
	private static final Logger log = LoggerFactory.getLogger(ServerPool.class);
	protected final NetworkParameters params;
	protected String[] fixservers;

	public ServerPool(NetworkParameters params) {
		this.params = params;
	}

	public ServerPool(NetworkParameters params, String[] fixservers) {
		this.fixservers = fixservers;
		this.params = params;
		for (String fixserver : this.fixservers) {
			try {
				addServer(fixserver);
			} catch (Exception e) {
				log.debug("", e);
			}
		}
	}

	// get a best server to be used and balance with random
	public ServerState getServer() {
		return servers.get(0);
	}

	public synchronized void addServer(String s) throws JsonProcessingException, IOException {
		long time = System.currentTimeMillis();
		// chain = getChainNumber(s);
		ServerState serverState = new ServerState();
		serverState.setServerurl(s);
		serverState.setResponseTime(System.currentTimeMillis() - time);
		// serverState.setChainlength(chain.getChainLength());
		servers.add(serverState);
		// Collections.sort(servers, new SortbyChain());
	}

	public synchronized void removeServer(String server) {
		for (Iterator<ServerState> iter = servers.listIterator(); iter.hasNext();) {
			ServerState a = iter.next();
			if (a.getServerurl().equals(server)) {
				iter.remove();
			}
		}
	}

	public synchronized void addServers(List<String> serverCandidates) {
		servers = new ArrayList<ServerState>();
		for (String s : serverCandidates) {
			try {
				addServer(s);
			} catch (Exception e) {
				log.debug(e.toString());
			}
		}
	}

	public List<ServerState> getServers() {
		return servers;
	}

	public void setServers(List<ServerState> servers) {
		this.servers = servers;
	}

}
