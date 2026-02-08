package net.bigtangle.seeds;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import org.springframework.boot.test.web.server.LocalServerPort;

import net.bigtangle.utils.Json;
import net.bigtangle.utils.OkHttp3Util;
//@Disabled("Temporarily disabled")
@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class RegisterRemoteTest extends AbstractIntegrationTest {
	private static final Logger logger = LoggerFactory.getLogger(RegisterRemoteTest.class);

	@LocalServerPort
	private int port;

	@Test
	public void testURL() throws Exception {
		HashMap<String, String> requestParam = new HashMap<String, String>();
		requestParam.put("url", "https://bigtangle.org:8088");
		requestParam.put("servertype", "bigtangle");
		byte[] data = OkHttp3Util.post("https://localhost:" + port + "/" + ReqCmd.register.name(),
				Json.jsonmapper().writeValueAsString(requestParam).getBytes());

		requestParam = new HashMap<String, String>();
		data = OkHttp3Util.post("https://localhost:" + port + "/" + ReqCmd.serverinfolist.name(),
				Json.jsonmapper().writeValueAsString(requestParam).getBytes());
		ServerinfoResponse response = Json.jsonmapper().readValue(data, ServerinfoResponse.class);
		if (response.getServerInfoList() != null) {
			for (ServerInfo serverInfo : response.getServerInfoList()) {
				logger.info(serverInfo.getUrl() + "," + serverInfo.getServertype());
				assertEquals(serverInfo.getUrl(), "https://bigtangle.de:8088");
			}
		}
	}

	 
}
