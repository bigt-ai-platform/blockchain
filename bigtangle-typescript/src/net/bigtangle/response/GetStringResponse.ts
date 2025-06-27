import { AbstractResponse } from './AbstractResponse';

export class GetStringResponse extends AbstractResponse {
    private text: string | null = null;

    public static create(text: string): GetStringResponse {
        const res = new GetStringResponse();
        res.text = text;
        return res;
    }

    public getText(): string | null {
        return this.text;
    }

    public setText(text: string | null): void {
        this.text = text;
    }
}
