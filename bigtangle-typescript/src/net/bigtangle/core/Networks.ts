import { NetworkParameters } from './NetworkParameters';
import { MainNetParams } from './MainNetParams';
import { TestNetParams } from './TestNetParams';

export class Networks {
    private static networks: NetworkParameters[] = [
        new MainNetParams(),
        new TestNetParams()
    ];

    public static get(): NetworkParameters[] {
        return this.networks;
    }

    public static register(network: NetworkParameters): void {
        if (!this.networks.some(n => n.getId() === network.getId())) {
            this.networks.push(network);
        }
    }
}
