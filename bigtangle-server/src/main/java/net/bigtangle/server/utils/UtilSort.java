package net.bigtangle.server.utils;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import net.bigtangle.core.Block;
import net.bigtangle.core.TXReward;
import net.bigtangle.core.UTXO;
import net.bigtangle.server.core.BlockWrap;

public class UtilSort {

	public void sortUTXO(List<UTXO> ulist) {
		Collections.sort(ulist, new SortbyUTXO());

	}

	public static  class SortbyUTXO implements Comparator<UTXO> {

		public int compare(UTXO a, UTXO b) {
			return a.getTime() >= b.getTime() ? -1 : 1;
		}
	}

	public void sortBlock(List<Block> ulist) {
		Collections.sort(ulist, new SortbyBlock());

	}
	
	public  void sortBlockWrap(List<BlockWrap> ulist) {
		Collections.sort(ulist, new SortBlockWrap());

	}
	
	public static class SortbyBlock implements Comparator<Block> {

		public int compare(Block a, Block b) {
			return a.getHeight()  >= b.getHeight() ? 1 : -1;
		}
	}
	public static  class SortBlockWrap implements Comparator<BlockWrap> {

		public int compare(BlockWrap a, BlockWrap b) {
			return a.getBlock().getHeight() >= b.getBlock().getHeight() ? 1 : -1;
		}
	}
	public static class SortbyChain implements Comparator<TXReward> {
		// Used for sorting in ascending order of
		// roll number
		public int compare(TXReward a, TXReward b) {
			return a.getChainLength()  <= b.getChainLength() ? 1 : -1;
		}
	}

	 

}
