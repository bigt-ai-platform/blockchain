/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.response;

public class ServerInfo {

	String url;
	String servertype;

	String chain;
	String status;

	public String getUrl() {
		return url;
	}

	public void setUrl(String url) {
		this.url = url;
	}

	public String getServertype() {
		return servertype;
	}

	public void setServertype(String servertype) {
		this.servertype = servertype;
	}

	public String getChain() {
		return chain;
	}

	public void setChain(String chain) {
		this.chain = chain;
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}
}
