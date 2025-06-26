// TypeScript translation of DomainnameUtil.java

export class DomainnameUtil {
    static matchParentDomainname(domainname?: string): string {
        if (!domainname || domainname.trim() === '') {
            return '';
        }
        if (domainname.indexOf('.') < 0) {
            return '';
        }
        return domainname.substring(domainname.indexOf('.') + 1);
    }
}
