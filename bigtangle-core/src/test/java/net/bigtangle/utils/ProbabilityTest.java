/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

package net.bigtangle.utils;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

public class ProbabilityTest {

   // @Test
    public void testSigns() throws Exception {

        for (int z = 1; z < 300; z++) {
            System.out.println(
                    z + "=" +ProbabilityBlock.attackerSuccessProbability(0.3, z));
        }

    }
   // @Test
    public void testRandomness() throws Exception {

	byte[] randomness = "test123".getBytes();
	Random se = new  Random(31243565477l);
	
	int randomWin = se.nextInt(10);
  
    for (int i=0; i<100;i++  ) {
    	  se = new  Random(31243565477l);
    	assertTrue(randomWin ==	se.nextInt(10)) ;
    }
	
    }
}
