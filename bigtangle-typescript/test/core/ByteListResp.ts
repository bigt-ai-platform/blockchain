
import { ByteResp } from './ByteResp';
import { AbstractResponse } from '../../src/net/bigtangle/response/AbstractResponse';

export class ByteListResp extends AbstractResponse {
    private list: ByteResp[] = [];

    public getList(): ByteResp[] {
        return this.list;
    }

    public setList(list: ByteResp[]): void {
        this.list = list;
    }
}
