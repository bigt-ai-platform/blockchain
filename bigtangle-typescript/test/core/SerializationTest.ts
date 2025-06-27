
import { Buffer } from 'buffer';
import { Sha256Hash } from '../../src/net/bigtangle/core/Sha256Hash';
import { ContactInfo } from '../../src/net/bigtangle/core/ContactInfo';
import { Contact } from '../../src/net/bigtangle/core/Contact';
import { OrderOpenInfo } from '../../src/net/bigtangle/core/OrderOpenInfo';
import { Side } from '../../src/net/bigtangle/core/Side';
import { NetworkParameters } from '../../src/net/bigtangle/core/NetworkParameters';
import { ContractEventInfo } from '../../src/net/bigtangle/core/ContractEventInfo';
import { OrderCancelInfo } from '../../src/net/bigtangle/core/OrderCancelInfo';
import { MyHomeAddress } from '../../src/net/bigtangle/core/MyHomeAddress';
import { RewardInfo } from '../../src/net/bigtangle/core/RewardInfo';
import { TokenInfo } from '../../src/net/bigtangle/core/TokenInfo';
import { Token } from '../../src/net/bigtangle/core/Token';
import { MultiSignAddress } from '../../src/net/bigtangle/core/MultiSignAddress';
import { KeyValue } from '../../src/net/bigtangle/core/KeyValue';
import { KeyValueList } from '../../src/net/bigtangle/core/KeyValueList';
import { IdentityCore } from '../../src/net/bigtangle/apps/data/IdentityCore';
import { IdentityData } from '../../src/net/bigtangle/apps/data/IdentityData';
import { SignedData } from '../../src/net/bigtangle/apps/data/SignedData';
import { ECKey } from '../../src/net/bigtangle/core/ECKey';
import { TokenKeyValues } from '../../src/net/bigtangle/core/TokenKeyValues';
import { Utils } from '../../src/net/bigtangle/utils/Utils';

describe('SerializationTest', () => {
    function getRandomSha256Hash(): Sha256Hash {
        const rawHashBytes = Buffer.alloc(32);
        for (let i = 0; i < rawHashBytes.length; i++) {
            rawHashBytes[i] = Math.floor(Math.random() * 256);
        }
        return Sha256Hash.wrap(rawHashBytes);
    }

    test('testContactInfoSerialization', () => {
        const info1 = new ContactInfo();
        info1.setVersion(3);
        const e = new Contact();
        e.setAddress('test1');
        e.setName('test2');
        info1.getContactList().push(e);
        const info2 = new ContactInfo().parse(info1.toByteArray());

        expect(Buffer.compare(info1.toByteArray(), info2.toByteArray())).toBe(0);
        expect(info1.getVersion()).toBe(info2.getVersion());
        expect(info1.getContactList()[0].getAddress()).toBe(
            info2.getContactList()[0].getAddress(),
        );
        expect(info1.getContactList()[0].getName()).toBe(
            info2.getContactList()[0].getName(),
        );
    });

    test('testContactInfo2Serialization', () => {
        const info1 = new ContactInfo();
        info1.setVersion(3);
        const e = new Contact();
        e.setAddress('test1');
        e.setName(null);
        info1.getContactList().push(e);
        const info2 = new ContactInfo().parse(info1.toByteArray());

        expect(Buffer.compare(info1.toByteArray(), info2.toByteArray())).toBe(0);
        expect(info1.getVersion()).toBe(info2.getVersion());
        expect(info1.getContactList()[0].getAddress()).toBe(
            info2.getContactList()[0].getAddress(),
        );
        expect(info1.getContactList()[0].getName()).toBe(
            info2.getContactList()[0].getName(),
        );
    });

    test('testOrderOpenInfoSerialization', () => {
        const info1 = new OrderOpenInfo(
            BigInt(2),
            'test1',
            Buffer.from([2]),
            BigInt(3),
            BigInt(4),
            Side.SELL,
            'test2',
            NetworkParameters.BIGTANGLE_TOKENID_STRING,
            BigInt(1),
            3,
            NetworkParameters.BIGTANGLE_TOKENID_STRING,
        );
        const info2 = new OrderOpenInfo().parse(info1.toByteArray());

        expect(Buffer.compare(info1.toByteArray(), info2.toByteArray())).toBe(0);
        expect(info1.getBeneficiaryAddress()).toBe(info2.getBeneficiaryAddress());
        expect(
            Buffer.compare(
                info1.getBeneficiaryPubKey(),
                info2.getBeneficiaryPubKey(),
            ),
        ).toBe(0);
        expect(info1.getTargetTokenid()).toBe(info2.getTargetTokenid());
        expect(info1.getTargetValue()).toBe(info2.getTargetValue());
        expect(info1.getValidFromTime()).toBe(info2.getValidFromTime());
        expect(info1.getValidToTime()).toBe(info2.getValidToTime());
        expect(info1.getVersion()).toBe(info2.getVersion());
    });

    test('testContractEventInfoSerialization', () => {
        const info1 = new ContractEventInfo(
            'contracttokenid',
            BigInt(1),
            'tokenid',
            'address',
            BigInt(3),
            BigInt(4),
            '',
        );
        const info2 = new ContractEventInfo().parse(info1.toByteArray());

        expect(Buffer.compare(info1.toByteArray(), info2.toByteArray())).toBe(0);
        expect(info1.getBeneficiaryAddress()).toBe(info2.getBeneficiaryAddress());
        expect(info1.getOfferValue()).toBe(info2.getOfferValue());
        expect(info1.getOfferTokenid()).toBe(info2.getOfferTokenid());
        expect(info1.getOfferSystem()).toBe(info2.getOfferSystem());
        expect(info1.getContractTokenid()).toBe(info2.getContractTokenid());
        expect(info1.getVersion()).toBe(info2.getVersion());
    });

    test('testOrderCancelInfoSerialization', () => {
        const info1 = new OrderCancelInfo(getRandomSha256Hash());
        const info2 = new OrderCancelInfo().parse(info1.toByteArray());

        expect(Buffer.compare(info1.toByteArray(), info2.toByteArray())).toBe(0);
        expect(info1.getBlockHash()).toEqual(info2.getBlockHash());
    });

    test('testMyHomeAddressSerialization', () => {
        const info1 = new MyHomeAddress();
        info1.setCity('test1');
        info1.setCountry('test2');
        info1.setEmail('test3');
        info1.setProvince('test4');
        info1.setRemark('test5');
        info1.setStreet('test6');
        const info2 = new MyHomeAddress().parse(info1.toByteArray());

        expect(Buffer.compare(info1.toByteArray(), info2.toByteArray())).toBe(0);
        expect(info1.getCity()).toBe(info2.getCity());
        expect(info1.getCountry()).toBe(info2.getCountry());
        expect(info1.getEmail()).toBe(info2.getEmail());
        expect(info1.getProvince()).toBe(info2.getProvince());
        expect(info1.getRemark()).toBe(info2.getRemark());
        expect(info1.getStreet()).toBe(info2.getStreet());
    });

    test('testRewardInfoSerialization', () => {
        const randomHash = getRandomSha256Hash();
        const blocks = new Set<Sha256Hash>();
        blocks.add(randomHash);
        const info1 = new RewardInfo(randomHash, 2, blocks, BigInt(2));
        const bytes1 = info1.toByteArray();
        const info2 = new RewardInfo().parse(bytes1);
        const bytes2 = info2.toByteArray();

        expect(Buffer.compare(bytes1, bytes2)).toBe(0);
        expect(info1.getPrevRewardHash()).toEqual(info2.getPrevRewardHash());
        expect(info1.getChainlength()).toBe(info2.getChainlength());
        expect(Array.from(info1.getBlocks())[0]).toEqual(
            Array.from(info2.getBlocks())[0],
        );
    });

    test('testTokenInfoSerialization', () => {
        const addresses: MultiSignAddress[] = [];
        const tokens = Token.buildSimpleTokenInfo(
            true,
            null,
            '2',
            '3',
            '4',
            2,
            3,
            BigInt(4),
            true,
            0,
            'de',
        );
        const info1 = new TokenInfo();
        info1.setToken(tokens);
        info1.setMultiSignAddresses(addresses);
        const bytes1 = info1.toByteArray();
        const info2 = new TokenInfo().parse(bytes1);
        const bytes2 = info2.toByteArray();

        expect(Buffer.compare(bytes1, bytes2)).toBe(0);
        expect(info1.getMultiSignAddresses().length).toBe(
            info2.getMultiSignAddresses().length,
        );
        expect(info1.getToken().getAmount()).toBe(info2.getToken().getAmount());
        expect(info1.getToken().getBlockHash()).toBe(
            info2.getToken().getBlockHash(),
        );
        expect(info1.getToken().getDescription()).toBe(
            info2.getToken().getDescription(),
        );
        expect(info1.getToken().getPrevblockhash()).toBe(
            info2.getToken().getPrevblockhash(),
        );
        expect(info1.getToken().getSignnumber()).toBe(
            info2.getToken().getSignnumber(),
        );
        expect(info1.getToken().getTokenid()).toBe(info2.getToken().getTokenid());
        expect(info1.getToken().getTokenindex()).toBe(
            info2.getToken().getTokenindex(),
        );
        expect(info1.getToken().getTokenname()).toBe(
            info2.getToken().getTokenname(),
        );
        expect(info1.getToken().getTokentype()).toBe(
            info2.getToken().getTokentype(),
        );
        expect(info1.getToken().getDomainName()).toBe(
            info2.getToken().getDomainName(),
        );
        expect(info1.getToken().isConfirmed()).toBe(
            info2.getToken().isConfirmed(),
        );
        expect(info1.getToken().isTokenstop()).toBe(
            info2.getToken().isTokenstop(),
        );
    });

    test('testKeyValueSerialization', () => {
        const kv = new KeyValue();
        kv.setKey('identity');
        kv.setValue('value');
        const bytes1 = kv.toByteArray();
        const k2 = new KeyValue().parse(bytes1);
        expect(kv.getKey()).toBe(k2.getKey());
        expect(kv.getValue()).toBe(k2.getValue());
    });

    test('testKeyValueListSerialization', () => {
        const kvs = new KeyValueList();

        const first = Buffer.from('my first file');
        let kv = new KeyValue();
        kv.setKey('myfirst');
        kv.setValue(Utils.HEX.encode(first));
        kvs.addKeyvalue(kv);
        kv = new KeyValue();
        kv.setKey('second.pdf');
        kv.setValue(Utils.HEX.encode(Buffer.from('second.pdf')));
        kvs.addKeyvalue(kv);
        const id = new KeyValueList().parse(kvs.toByteArray());

        expect(id.getKeyvalues().length).toBe(2);
    });

    test('testIdentityCoreSerialization', () => {
        const identityCore = new IdentityCore();
        identityCore.setSurname('zhang');
        identityCore.setForenames('san');
        identityCore.setSex('man');
        identityCore.setDateofissue('20200101');
        identityCore.setDateofexpiry('20201231');

        const id = new IdentityCore().parse(identityCore.toByteArray());
        expect(id.getDateofissue()).toBe('20200101');
    });

    test('testIdentityCoreDataSerialization', () => {
        const identityCore = new IdentityCore();
        identityCore.setSurname('zhang');
        identityCore.setForenames('san');
        identityCore.setSex('man');
        identityCore.setDateofissue('20200101');
        identityCore.setDateofexpiry('20201231');
        const identityData = new IdentityData();
        identityData.setIdentityCore(identityCore);
        identityData.setIdentificationnumber('120123456789012345');
        identityCore.setDateofbirth('20201231');
        identityData.setPhoto(Buffer.from('readFile'));
        identityData.setIdentityCore(identityCore);
        const id = new IdentityData().parse(identityData.toByteArray());
        expect(id.getIdentificationnumber()).toBe('120123456789012345');
        expect(identityData.uniqueNameIdentity()).toBe(
            identityData.uniqueNameIdentity(),
        );
        const identityData2 = new IdentityData();
        identityData2.setIdentificationnumber('546120123456789012345');
        expect(identityData.uniqueNameIdentity()).not.toBe(
            identityData2.uniqueNameIdentity(),
        );
        const identityData3 = new IdentityData();
        expect(identityData.uniqueNameIdentity()).not.toBe(
            identityData3.uniqueNameIdentity(),
        );
        const identityData4 = new IdentityData();
        identityData2.setIdentificationnumber(null);
        expect(identityData4.uniqueNameIdentity()).toBe(
            identityData4.uniqueNameIdentity(),
        );
        expect(identityData3.uniqueNameIdentity()).toBe(
            identityData4.uniqueNameIdentity(),
        );
    });

    test.skip('testIdentitySerialization', () => {
        // ECIES encryption not available in TypeScript
    });
});
