package net.bigtangle.server.data;

import net.bigtangle.core.Sha256Hash;

public  class  Rating  {
    private Sha256Hash blockhash;
    private long rating ;
    
    
    public Rating(Sha256Hash blockhash, long rating) {
        super();
        this.blockhash = blockhash;
        this.rating = rating;
    }
    public Sha256Hash getBlockhash() {
        return blockhash;
    }
    public void setBlockhash(Sha256Hash blockhash) {
        this.blockhash = blockhash;
    }
    public long getRating() {
        return rating;
    }
    public void setRating(long rating) {
        this.rating = rating;
    }
    @Override
    public String toString() {
        return "Rating [blockhash=" + blockhash + ", rating=" + rating + "]";
    }
    
}