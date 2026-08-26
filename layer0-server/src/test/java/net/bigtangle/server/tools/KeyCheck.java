import net.bigtangle.core.PQKey;
import net.bigtangle.core.Address;
import net.bigtangle.core.Utils;
import net.bigtangle.params.MainNetParams;
public class KeyCheck {
  public static void main(String[] a) throws Exception {
    byte[] seed = Utils.HEX.decode(a[0].trim());
    PQKey k = PQKey.fromMLDSA(seed);
    System.out.println("pub=" + k.getPublicKeyAsHex());
    System.out.println("addr=" + Address.fromHash160(MainNetParams.get(), k.getPubKeyHash()).toBase58());
  }
}
