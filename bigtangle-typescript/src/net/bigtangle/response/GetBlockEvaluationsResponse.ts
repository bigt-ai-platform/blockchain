import { AbstractResponse } from './AbstractResponse';
import { BlockEvaluationDisplay } from '../core/BlockEvaluationDisplay';

export class GetBlockEvaluationsResponse extends AbstractResponse {
    private evaluations: BlockEvaluationDisplay[] | null = null;

    public static create(evaluations: BlockEvaluationDisplay[]): GetBlockEvaluationsResponse {
        const res = new GetBlockEvaluationsResponse();
        res.evaluations = evaluations;
        return res;
    }

    public getEvaluations(): BlockEvaluationDisplay[] | null {
        return this.evaluations;
    }
}
