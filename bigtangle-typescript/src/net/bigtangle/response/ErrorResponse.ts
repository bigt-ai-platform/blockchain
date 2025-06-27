import { AbstractResponse } from './AbstractResponse';

export class ErrorResponse extends AbstractResponse {
    public static create(error: number): ErrorResponse {
        const res = new ErrorResponse();
        res.setErrorcode(error);
        return res;
    }
}
