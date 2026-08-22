package net.bigtangle.server;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.google.common.base.Stopwatch;

import jakarta.servlet.http.HttpServletResponse;
import net.bigtangle.params.ReqCmd;
import net.bigtangle.store.BlockStoreInterface;

/**
 * Layer-1 payment node REST API. Shares the common handlers (getTip, saveBlock,
 * getOutputs, ...) with the other L1 layers via {@link BaseDispatcherController}.
 */
@RestController
@RequestMapping("/")
public class DispatcherController extends BaseDispatcherController {

	@Override
	protected String getChainName() {
		return "Bigtangle Payment L1";
	}

	@Override
	protected boolean handleLayerSpecific(ReqCmd reqCmd, byte[] bodyByte, HttpServletResponse httpServletResponse,
			Stopwatch watch, BlockStoreInterface store, String reqCmdName) throws Exception {
		return false;
	}
}
