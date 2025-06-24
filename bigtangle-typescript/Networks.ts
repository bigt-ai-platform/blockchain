import { NetworkParameters } from './NetworkParameters';
import { MainNetParams } from './MainNetParams';

export class Networks {
    private static networks: NetworkParameters[] = [MainNetParams.get()];

    public static get(): NetworkParameters[] {
        return Networks.networks;
    }

    public static register(network: NetworkParameters): void {
        Networks.registerAll([network]);
    }

    public static registerAll(networks: NetworkParameters[]): void {
        Networks.networks = [...Networks.networks, ...networks];
    }

    public static unregister(network: NetworkParameters): void {
        Networks.networks = Networks.networks.filter(p => p !== network);
    }
}
