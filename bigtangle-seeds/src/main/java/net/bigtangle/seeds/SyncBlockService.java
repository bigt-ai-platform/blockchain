/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.seeds;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;

import net.bigtangle.core.TXReward;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.params.MainNetParams;
import net.bigtangle.response.GetTXRewardResponse;
import net.bigtangle.utils.Json;
import net.bigtangle.utils.OkHttp3Util;

/**
 * <p>
 * Provides services for sync blocks from remote servers via p2p.
 * 
 * sync remote chain data from chainlength, if chainlength = null, then sync the
 * chain data from the total rating with chain 100% For the sync from given
 * checkpoint, the server must be restarted.
 * 
 * 
 * </p>
 */
@Service
public class SyncBlockService {

	// @Autowired
	// protected NetworkParameters networkParameters;

	private static final Logger log = LoggerFactory.getLogger(SyncBlockService.class);

	// default start sync of chain and non chain data
	public void startSingleProcess() throws BlockStoreException, JsonProcessingException, IOException {

		log.debug(" Start syncServerInfo  : ");
		// syncServerInfo();
		localFileServerInfoWrite();
		log.debug(" end syncServerInfo: ");

	}

	/*
	 * write to local file and read it at start only
	 */
	public static void localFileServerInfoWrite() throws JsonProcessingException, IOException {

		if (DispatcherController.serverinfoList != null) {
			String path = DispatcherController.PATH;

			File file = new File(path);
			if (file.exists()) {
				file.delete();
			}
			String jsonString = Json.jsonmapper().writeValueAsString(DispatcherController.serverinfoList);
			writeString2File(jsonString, path);
		}

	}

	public static File writeString2File(String Data, String filePath)

	{

		BufferedReader bufferedReader = null;

		BufferedWriter bufferedWriter = null;

		File distFile = null;

		try {

			distFile = new File(filePath);

			if (!distFile.getParentFile().exists())
				distFile.getParentFile().mkdirs();

			bufferedReader = new BufferedReader(new StringReader(Data));

			bufferedWriter = new BufferedWriter(new FileWriter(distFile));

			char buf[] = new char[1024]; // 字符缓冲区

			int len;

			while ((len = bufferedReader.read(buf)) != -1)

			{

				bufferedWriter.write(buf, 0, len);

			}

			bufferedWriter.flush();

			bufferedReader.close();

			bufferedWriter.close();

		} catch (Exception e) {
			log.error("", e);
		}

		return distFile;

	}

	public void syncServerInfo() throws JsonProcessingException, IOException {

		List<String> badserver = new ArrayList<String>();
		byte[] data = null;

		for (String s : MainNetParams.get().serverSeeds()) {

			HashMap<String, String> requestParam = new HashMap<String, String>();

			data = OkHttp3Util.post(s + ReqCmd.serverinfolist.name(),
					Json.jsonmapper().writeValueAsString(requestParam).getBytes());
			ServerinfoResponse response = Json.jsonmapper().readValue(data, ServerinfoResponse.class);
			checkChain(response);

		}

	}

	public static void checkChain(ServerinfoResponse response) throws JsonProcessingException, IOException {
		// update the list DispatcherController.serverinfo;

		if (response.getServerInfoList() != null) {
			for (ServerInfo serverInfo : response.getServerInfoList()) {
				try {
					TXReward txReward = getMaxConfirmedReward(serverInfo.getUrl());
					serverInfo.status = "active";
				} catch (Exception e) {
					serverInfo.status = "inactive";
					log.error("", e);
				}

			}
			DispatcherController.serverinfoList = response.getServerInfoList();
			localFileServerInfoWrite();
		}
	}

	/*
	 * last chain max
	 */

	public static TXReward getMaxConfirmedReward(String server) throws JsonProcessingException, IOException {

		HashMap<String, String> requestParam = new HashMap<String, String>();

		byte[] response = OkHttp3Util.postString(server.trim() + "/" + ReqCmd.getChainNumber,
				Json.jsonmapper().writeValueAsString(requestParam));
		GetTXRewardResponse aTXRewardResponse = Json.jsonmapper().readValue(response, GetTXRewardResponse.class);

		return aTXRewardResponse.getTxReward();

	}

}
