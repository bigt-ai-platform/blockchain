
import { Buffer } from 'buffer';
import { BaseTaggableObject } from '../../src/net/bigtangle/utils/BaseTaggableObject';

describe('BaseTaggableObjectTest', () => {
    let obj: BaseTaggableObject;

    beforeEach(() => {
        obj = new BaseTaggableObject();
    });

    test('tags', () => {
        expect(obj.maybeGetTag('foo')).toBeNull();
        obj.setTag('foo', Buffer.from('bar', 'utf-8'));
        expect(obj.getTag('foo').toString('utf-8')).toBe('bar');
    });

    test('exception', () => {
        expect(() => {
            obj.getTag('non existent');
        }).toThrow();
    });
});
