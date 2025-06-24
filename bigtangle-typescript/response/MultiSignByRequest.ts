import { MultiSignBy } from '../MultiSignBy';

export class MultiSignByRequest {
    private multiSignBies: MultiSignBy[];

    constructor(multiSignBies: MultiSignBy[]) {
        this.multiSignBies = multiSignBies;
    }

    static create(multiSignBies: MultiSignBy[]): MultiSignByRequest {
        return new MultiSignByRequest(multiSignBies);
    }

    getMultiSignBies(): MultiSignBy[] {
        return this.multiSignBies;
    }

    setMultiSignBies(multiSignBies: MultiSignBy[]): void {
        this.multiSignBies = multiSignBies;
    }
}
