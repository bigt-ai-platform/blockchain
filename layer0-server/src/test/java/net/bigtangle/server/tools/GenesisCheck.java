import net.bigtangle.core.UtilGeneseBlock;
import net.bigtangle.params.MainNetParams;
public class GenesisCheck {
  public static void main(String[] a){
    System.out.println(UtilGeneseBlock.createGenesis(MainNetParams.get()).getHash());
  }
}
