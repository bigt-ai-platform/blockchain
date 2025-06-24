import { MultiSign } from '../MultiSign';

export class MultiSignResponse {
    private multiSigns: MultiSign[];

    constructor(multiSigns: MultiSign[]) {
        this.multiSigns = multiSigns;
    }

    getMultiSigns(): MultiSign[] {
        return this.multiSigns;
    }

    setMultiSigns(multiSigns: MultiSign[]): void {
        this.multiSigns = multiSigns;
    }
}
