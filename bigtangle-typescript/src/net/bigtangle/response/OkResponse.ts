import { AbstractResponse } from './AbstractResponse';

export class OkResponse extends AbstractResponse {
    public static create(): OkResponse {
        const res = new OkResponse();
        res.setErrorcode(0);
        return res;
    }
}
