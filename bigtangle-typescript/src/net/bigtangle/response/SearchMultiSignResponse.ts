import { AbstractResponse } from './AbstractResponse';

export class SearchMultiSignResponse extends AbstractResponse {
    private multiSignList: Map<string, any>[] = [];

    public static createSearchMultiSignResponse(multiSignList: Map<string, any>[]): SearchMultiSignResponse {
        const res = new SearchMultiSignResponse();
        res.multiSignList = multiSignList;
        return res;
    }

    public getMultiSignList(): Map<string, any>[] {
        return this.multiSignList;
    }

    public setMultiSignList(multiSignList: Map<string, any>[]): void {
        this.multiSignList = multiSignList;
    }
}
