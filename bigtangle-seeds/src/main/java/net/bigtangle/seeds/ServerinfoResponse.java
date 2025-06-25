package net.bigtangle.seeds;

import java.util.List;

import net.bigtangle.response.AbstractResponse;

public class ServerinfoResponse extends AbstractResponse {
	private List<ServerInfo> serverInfoList;

	public static ServerinfoResponse create(List<ServerInfo> serverInfoList) {
		ServerinfoResponse res = new ServerinfoResponse();
		res.serverInfoList = serverInfoList;
		return res;
	}

	public List<ServerInfo> getServerInfoList() {
		return serverInfoList;
	}

	public void setServerInfoList(List<ServerInfo> serverInfoList) {
		this.serverInfoList = serverInfoList;
	}
}
