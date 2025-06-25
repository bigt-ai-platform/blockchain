/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.response;

public class GenericResponse<T> extends AbstractResponse {

    private T data;

    public void set(T t) { this.data = t; }
    public T get() { return data; }

}
