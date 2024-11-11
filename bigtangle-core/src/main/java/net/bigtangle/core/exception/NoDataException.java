
/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

package net.bigtangle.core.exception;

/**
 * Thrown to indicate that there is no data.
 */
public class NoDataException extends Exception {
    private static final long serialVersionUID = 1L;

    public NoDataException() {
    }
    public NoDataException( String message) {
        super(message);
     
    }
}
