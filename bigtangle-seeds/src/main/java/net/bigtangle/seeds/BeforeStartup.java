/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.seeds;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
 

import jakarta.annotation.PostConstruct;

@Component
public class BeforeStartup {

	private static final Logger logger = LoggerFactory.getLogger(BeforeStartup.class);

	@PostConstruct
	public void run() throws Exception {

		/*
		 * At Start read server info list
		 */
		String path = DispatcherController.PATH;
		File file = new File(path);
		if (file.exists()) {
			String jsonString = file2String(file);
			if (jsonString != null && !"".equals(jsonString.trim())) {
				JSONArray jsonArray = new JSONArray(jsonString);
				for (int i = 0; i < jsonArray.length(); i++) {
					JSONObject jsonObject = jsonArray.getJSONObject(i);
					ServerInfo serverInfo = new ServerInfo();
					serverInfo.setUrl(jsonObject.getString("url"));
					serverInfo.setServertype(jsonObject.getString("servertype"));
					serverInfo.setChain(jsonObject.getString("chain"));
					serverInfo.setStatus(jsonObject.getString("status"));
					if (DispatcherController.serverinfoList == null) {
						DispatcherController.serverinfoList = new ArrayList<ServerInfo>();

					}
					DispatcherController.serverinfoList.add(serverInfo);

				}

			}
		} else {
			initSetting();
		}

	}

	public void initSetting() {
		if (DispatcherController.serverinfoList == null) {
			ServerInfo serverInfo = new ServerInfo();
			serverInfo.setUrl("https://p.bigtangle.org:8088/");
			serverInfo.setServertype("");
			serverInfo.setStatus("active");

			List<ServerInfo> serverinfoList = new ArrayList<ServerInfo>();
			try {
				SyncBlockService.getMaxConfirmedReward(serverInfo.getUrl());
				serverinfoList.add(serverInfo);
				DispatcherController.serverinfoList = serverinfoList;
			} catch (Exception e) {
				logger.error("", e);
			}

		}

	}

	public String file2String(final File file) throws IOException {
		if (file.exists()) {
			byte[] data = new byte[(int) file.length()];
			boolean result;
			FileInputStream inputStream = null;
			try {
				inputStream = new FileInputStream(file);
				int len = inputStream.read(data);
				result = len == data.length;
			} finally {
				if (inputStream != null) {
					inputStream.close();
				}
			}
			if (result) {
				return new String(data);
			}
		}
		return null;
	}

}
