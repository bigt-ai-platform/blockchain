import { DataClass } from './DataClass';
import { Uploadfile } from './Uploadfile';
import { Json } from '../utils/Json';

export class UploadfileInfo extends DataClass {
    private fUploadfiles: Uploadfile[] = [];

    public toByteArray(): Uint8Array {
        try {
            const jsonStr = Json.jsonmapper().writeValueAsString(this);
            return new TextEncoder().encode(jsonStr);
        } catch (e: any) {
            throw new Error(e);
        }
    }

    public parse(buf: Uint8Array): UploadfileInfo {
        const jsonStr = new TextDecoder('utf-8').decode(buf);
        const uploadfileInfo = Json.jsonmapper().readValue(jsonStr, UploadfileInfo);
        if (uploadfileInfo === null) {
            return this;
        }
        this.fUploadfiles = uploadfileInfo.getfUploadfiles();
        return this;
    }

    public getfUploadfiles(): Uploadfile[] {
        return this.fUploadfiles;
    }

    public setfUploadfiles(fUploadfiles: Uploadfile[]): void {
        this.fUploadfiles = fUploadfiles;
    }
}
