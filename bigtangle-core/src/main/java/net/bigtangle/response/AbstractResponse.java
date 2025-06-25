/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

package net.bigtangle.response;

public abstract class AbstractResponse  {
 

	private Integer errorcode;

    private String message;

    private Long duration;
    
 
  
    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

 
    public Integer getErrorcode() {
        return errorcode;
    }

    public void setErrorcode(Integer errorcode) {
        this.errorcode = errorcode;
    }

    public static AbstractResponse createEmptyResponse() {
        return new Emptyness();
    }

    private static class Emptyness extends AbstractResponse {
 
    }
   public Long getDuration() {
        return duration == null ? 0 : this.duration;
    }

    public void setDuration(Long duration) {
        this.duration = duration;
    }
}
