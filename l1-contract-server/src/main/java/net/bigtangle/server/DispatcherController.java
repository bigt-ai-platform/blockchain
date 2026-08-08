package net.bigtangle.server;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.google.common.base.Stopwatch;

import jakarta.servlet.http.HttpServletResponse;
import net.bigtangle.params.ReqCmd;
import net.bigtangle.store.BlockStoreInterface;

/**
 * Layer-1 contract/EVM node REST API. Shares the common handlers with the
 * other L1 layers via {@link BaseDispatcherController}; the contract tables
 * are exposed through the EVM endpoints (see {@code EVMController}).
 */
@RestController
@RequestMapping("/")
public class DispatcherController extends BaseDispatcherController {

	@Override
	protected String getChainName() {
		return "Bigtangle";
	}

	@Override
	protected boolean handleLayerSpecific(ReqCmd reqCmd, byte[] bodyByte, HttpServletResponse httpServletResponse,
			Stopwatch watch, BlockStoreInterface store, String reqCmdName) throws Exception {
		return false;
	}
}
