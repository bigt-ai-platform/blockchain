/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.utils;

import java.io.IOException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.util.zip.GZIPInputStream;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;

import net.bigtangle.core.Utils;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;
import okio.Okio;

public class OkHttp3Util {

    private static final Logger logger = LoggerFactory.getLogger(OkHttp3Util.class);

    public static long timeoutMinute = 45;
    private static OkHttpClient client = null;
    public static String pubkey;
    public static String signHex;
    public static String contentHex;

    /*
     * same method, but it will call next server, if last server failed to
     * return result
     */
    public static byte[] post(String[] url, byte[] b) throws IOException {
        return post(url, b, 0);
    }

    public static byte[] post(String[] url, byte[] b, int number) throws IOException {

        if (number < url.length) {
            try {
                return post(url[number], b);
            } catch (RuntimeException e) {
                number += 1;
                return post(url, b, number);
            }
        } else {
            throw new RuntimeException("all servers are failed:  " + Arrays.toString(url));
        }
    }

    public static byte[] post(String[] url, String b) throws IOException {
        return post(url, b, 0);
    }

    public static byte[] post(String[] url, String b, int number) throws IOException {

        if (number < url.length) {
            try {
                return postAndGetBlock(url[number], b);
            } catch (RuntimeException e) {
                number += 1;
                return post(url, b, number);
            }
        } else {
            throw new RuntimeException("all servers are failed:  " + Arrays.toString(url));
        }
    }

    public static byte[] post(String url, byte[] b) throws IOException {
        logger.debug("start:  " + url);
        OkHttpClient client = getOkHttpClient();
        RequestBody body = RequestBody.create(MediaType.parse("application/octet-stream; charset=utf-8"), b);
        Request request = new Request.Builder().url(url).post(body).build();
        Response response = client.newCall(request).execute();
        try {
            if (!response.isSuccessful()) {
                throw new RuntimeException("Server:" + url + "  HTTP  Error: " + response);
            }

            byte[] resp = response.body().bytes();
            // Handle gzip-compressed responses from the legacy server
            if (resp.length > 2 && resp[0] == (byte) 0x1F && resp[1] == (byte) 0x8B) {
                try (java.util.zip.GZIPInputStream gzip = new java.util.zip.GZIPInputStream(
                        new java.io.ByteArrayInputStream(resp))) {
                    resp = gzip.readAllBytes();
                }
            }
            // logger.debug(resp);
            checkResponse(resp, url);
            return resp;

        } finally {
            // client.cache().close();
            response.close();
            response.body().close();
        }
    }

    @SuppressWarnings("unchecked")
    public static byte[] postAndGetBlock(String url, String s) throws IOException {

        HashMap<String, Object> result = Json.jsonmapper().readValue(postString(url, s), HashMap.class);
        String dataHex = (String) result.get("dataHex");
        if (dataHex != null) {
            return Utils.HEX.decode(dataHex);
        } else {
            return null;
        }

    }

    public static byte[] postString(String url, String s) throws IOException {
        logger.debug("start:  " + url );
        OkHttpClient client = getOkHttpClient();
        RequestBody body = RequestBody.create(MediaType.parse("application/octet-stream; charset=utf-8"), s);
        Request request = new Request.Builder().url(url).post(body).build();
        Response response = client.newCall(request).execute();

        try {
            if (!response.isSuccessful()) {
                throw new RuntimeException("Server:" + url + "  HTTP  Error: " + response);
            }
            byte[] resp = response.body().bytes();
            checkResponse(resp, url);
            return resp;
        } finally {
            response.close();
            response.body().close();
        }
    }

    public static void checkResponse(byte[] resp, String url)
            throws JsonParseException, JsonMappingException, IOException {

        if ( resp ==null)
            return;
        @SuppressWarnings("unchecked")
        HashMap<String, Object> result2 = Json.jsonmapper().readValue(resp, HashMap.class);
        if (result2.get("errorcode") != null) {
            int error = (Integer) result2.get("errorcode");
            if (error > 0) {
                if (result2.get("message") == null) {
                    throw new RuntimeException("Server:" + url + " Server Error: " + error);
                } else {

                    throw new RuntimeException("Server:" + url + " Server Error: " + result2.get("message"));
                }
            }
        }
    }

    private static OkHttpClient getOkHttpClient() {
        if (client == null)
            client = getUnsafeOkHttpClient();
        return client;

    }

    public static OkHttpClient getUnsafeOkHttpClient() {
        try {

            X509TrustManager tr = new X509TrustManager() {
                @Override
                public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType)
                        throws CertificateException {
                }

                @Override
                public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType)
                        throws CertificateException {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }

            };

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[] { tr }, null);
            SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();

            OkHttpClient client = new OkHttpClient.Builder().sslSocketFactory(sslSocketFactory, tr)
                    .hostnameVerifier(new HostnameVerifier() {
                        @Override
                        public boolean verify(String hostname, SSLSession session) {
                            return true;
                        }
                    }).connectTimeout(timeoutMinute, TimeUnit.MINUTES).writeTimeout(timeoutMinute, TimeUnit.MINUTES)
                    .addInterceptor(new BasicAuthInterceptor(pubkey, signHex, contentHex))
                    .readTimeout(timeoutMinute, TimeUnit.MINUTES).build();
            return client;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * This interceptor compresses the HTTP request body. Many webservers can't
     * handle this!
     */

    public static byte[] post(String url, byte[] b, String header) throws IOException {
        logger.debug("start:  " + url);
        OkHttpClient client = getOkHttpClient();
        RequestBody body = RequestBody.create(MediaType.parse("application/octet-stream; charset=utf-8"), b);
        Request request = new Request.Builder().url(url).post(body).addHeader("accessToken", header).build();

        Response response = client.newCall(request).execute();
        try {
            if (!response.isSuccessful()) {
                throw new RuntimeException("Server:" + url + "  HTTP  Error: " + response);
            }

            byte[] resp = response.body().bytes();
            // logger.debug(resp);
            checkResponse(resp, url);
            return resp;

        } finally {
            // client.cache().close();
            response.close();
            response.body().close();
        }
    }

    public static byte[] postString(String url, String s, String header) throws IOException {
        logger.debug(url);
        logger.debug(header);
        OkHttpClient client = getOkHttpClient();
        RequestBody body = RequestBody.create(MediaType.parse("application/octet-stream; charset=utf-8"), s);
        Request request = new Request.Builder().url(url).addHeader("accessToken", header).post(body).build();
        Response response = client.newCall(request).execute();

        try {
            if (!response.isSuccessful()) {
                throw new RuntimeException("Server:" + url + "  HTTP  Error: " + response);
            }
            byte[] resp = response.body().bytes();
            checkResponse(resp, url);
            return resp;
        } finally {
            response.close();
            response.body().close();
        }
    }

    public static byte[] postAndGetBlock(String url, String s, String header) throws IOException {
        // return response.body().bytes();
        byte[] resp = postString(url, s, header);
        if (resp == null)
            return null;

        @SuppressWarnings("unchecked")
        HashMap<String, Object> result = Json.jsonmapper().readValue(resp, HashMap.class);
        String dataHex = (String) result.get("dataHex");
        if (dataHex != null) {
            return Utils.HEX.decode(dataHex);
        } else {
            return null;
        }
    }

}
