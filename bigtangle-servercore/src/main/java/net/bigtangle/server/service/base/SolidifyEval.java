package net.bigtangle.server.service.base;

import net.bigtangle.core.Block;
import net.bigtangle.server.data.SolidityState;

/** Immutable evaluation-phase decision for one referenced block
 *  (see ServiceBase.solidifyBlocks parallel branch). */
enum SolidifyDecision { SKIP_DONE, WRITE }

final class SolidifyEval {
    final Block block;
    final SolidityState state;
    final SolidifyDecision decision;
    SolidifyEval(Block b, SolidityState st, SolidifyDecision d) {
        block = b; state = st; decision = d;
    }
}
