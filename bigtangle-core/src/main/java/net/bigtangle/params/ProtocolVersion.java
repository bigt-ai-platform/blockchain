package net.bigtangle.params;

public enum ProtocolVersion {
	MINIMUM(70000), PONG(60001), BLOOM_FILTER(70000), CURRENT(70001);

	private final int bitcoinProtocol;

	ProtocolVersion(final int bitcoinProtocol) {
		this.bitcoinProtocol = bitcoinProtocol;
	}

	public int getBitcoinProtocolVersion() {
		return bitcoinProtocol;
	}


}
