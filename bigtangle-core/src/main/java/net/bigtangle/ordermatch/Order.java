/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.ordermatch;

public class Order {

    private PriceLevel level;

    private String id;

    private long remainingQuantity;

    public Order(PriceLevel level, String id, long size) {
        this.level = level;

        this.id = id;

        this.remainingQuantity = size;
    }

    public PriceLevel getLevel() {
        return level;
    }

    public String getId() {
        return id;
    }

    public long getRemainingQuantity() {
        return remainingQuantity;
    }

    public void reduce(long quantity) {
        remainingQuantity -= quantity;
    }

    public void resize(long size) {
        remainingQuantity = size;
    }

}
