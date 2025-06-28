// Utility class to hold all registered NetworkParameters types
// Adapted from Java: net.bigtangle.params.Networks

import { NetworkParameters } from './NetworkParameters';
import { MainNetParams } from './MainNetParams';

export class Networks {
    private static networks: Set<NetworkParameters> = new Set([MainNetParams.get()]);

    static get(): Set<NetworkParameters> {
        return this.networks;
    }

    static register(network: NetworkParameters): void {
        this.networks.add(network);
    }

    static registerMany(networks: NetworkParameters[]): void {
        for (const net of networks) {
            this.networks.add(net);
        }
    }

    static unregister(network: NetworkParameters): void {
        this.networks.delete(network);
    }
}
