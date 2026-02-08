package net.bigtangle.server.utils;

import java.util.Comparator;
import java.util.List;

import net.bigtangle.core.UTXO;

public class UtilSort {

	public void sortUTXO(List<UTXO> ulist) {
		ulist.sort(new SortbyUTXO());

	}

	public static  class SortbyUTXO implements Comparator<UTXO> {

		public int compare(UTXO a, UTXO b) {
			return a.getTime() >= b.getTime() ? -1 : 1;
		}
	}


}
