import { MultiSignBy } from '../core/MultiSignBy';

export class MultiSignByRequest {
    private multiSignBies: MultiSignBy[] = [];

    public getMultiSignBies(): MultiSignBy[] {
        return this.multiSignBies;
    }

    public setMultiSignBies(multiSignBies: MultiSignBy[]): void {
        this.multiSignBies = multiSignBies;
    }
    
    public static create(multiSignBies: MultiSignBy[]): MultiSignByRequest {
        const res = new MultiSignByRequest();
        res.multiSignBies = multiSignBies;
        return res;
    }
}