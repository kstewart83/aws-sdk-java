/*
 * Copyright 2011-2015 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.amazonaws.http;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.apache.http.Header;
import org.apache.http.HeaderElement;
import org.apache.http.HttpEntity;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.HttpResponse;
import org.apache.http.HttpResponseInterceptor;
import org.apache.http.ProtocolException;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.ChallengeState;
import org.apache.http.auth.NTCredentials;
import org.apache.http.client.AuthCache;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.RedirectStrategy;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.GzipDecompressingEntity;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.protocol.ClientContext;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.config.SocketConfig;
import org.apache.http.conn.ConnectTimeoutException;
import org.apache.http.conn.scheme.SchemeLayeredSocketFactory;
import org.apache.http.conn.scheme.SchemeSocketFactory;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.DefaultHostnameVerifier;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.BasicAuthCache;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.HttpContext;
import org.apache.http.ssl.SSLContexts;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.http.conn.ssl.SdkTLSSocketFactory;
import com.amazonaws.http.impl.client.HttpRequestNoRetryHandler;
import com.amazonaws.http.impl.client.SdkHttpClient;

/** Responsible for creating and configuring instances of Apache HttpClient4. */
class HttpClientFactory {


    /**
     * Creates a new HttpClient object using the specified AWS
     * ClientConfiguration to configure the client.
     *
     * @param config
     *            Client configuration options (ex: proxy settings, connection
     *            limits, etc).
     *
     * @return The new, configured HttpClient.
     */
public CloseableHttpClient createHttpClient(ClientConfiguration config) {
    	
        SSLContext sslcontext = SSLContexts.createSystemDefault();
        SdkTLSSocketFactory sslsf = new SdkTLSSocketFactory(
                sslcontext,
                new String[] { "TLSv1" },
                null,
                new DefaultHostnameVerifier());
        
    	
    	Registry<ConnectionSocketFactory> socketFactoryRegistry = RegistryBuilder.<ConnectionSocketFactory> create()
    			.register("https", sslsf)
    			.register("http", PlainConnectionSocketFactory.getSocketFactory())
    			.build();
    	
        PoolingHttpClientConnectionManager connectionManager = ConnectionManagerFactory
                .createPoolingClientConnManager(config, socketFactoryRegistry);
        
        HttpClientBuilder httpClient = SdkHttpClient.custom(connectionManager);
    	
    	RequestConfig.Builder requestConfig = RequestConfig.custom()
    			.setConnectTimeout(config.getConnectionTimeout())
    			.setSocketTimeout(config.getSocketTimeout())
    			//.setStaleConnectionCheckEnabled(true)
    			//.setExpectContinueEnabled(true);
    			;
    	
    	if (config.getLocalAddress() != null) {
            requestConfig = requestConfig.setLocalAddress(config.getLocalAddress());
        }
    	
    	
    	 /* Set proxy if configured */
        String proxyHost = config.getProxyHost();
        int proxyPort = config.getProxyPort();
        if (proxyHost != null && proxyPort > 0) {
        	// might want to have an option to enable/disable logging of
        	// internal SDK things like this log event
        	//AmazonHttpClient.log.info("Configuring Proxy. Proxy Host: " + proxyHost + " " + "Proxy Port: " + proxyPort);
            HttpHost proxyHttpHost = new HttpHost(proxyHost, proxyPort);
            requestConfig = requestConfig.setProxy(proxyHttpHost);
            
            String proxyUsername    = config.getProxyUsername();
            String proxyPassword    = config.getProxyPassword();
            String proxyDomain      = config.getProxyDomain();
            String proxyWorkstation = config.getProxyWorkstation();
            
            if (proxyUsername != null && proxyPassword != null) {
            	CredentialsProvider credsProvider = new BasicCredentialsProvider();
                credsProvider.setCredentials(
                		new AuthScope(proxyHost, proxyPort),
                        new NTCredentials(proxyUsername, proxyPassword, proxyWorkstation, proxyDomain));
                
            	httpClient = httpClient
            			.setDefaultCredentialsProvider(credsProvider);
            	
            	// Add a request interceptor that sets up proxy authentication pre-emptively if configured
                if (config.isPreemptiveBasicProxyAuth()){
                	httpClient = httpClient.addInterceptorFirst(new PreemptiveProxyAuth(proxyHttpHost));
                }
            }
        }
    	
    	SocketConfig.Builder socketConfig = SocketConfig.custom()
    			.setTcpNoDelay(true)
    			.setSoKeepAlive(config.useTcpKeepAlive());
    	
    	int socketSendBufferSizeHint = config.getSocketBufferSizeHints()[0];
        int socketReceiveBufferSizeHint = config.getSocketBufferSizeHints()[1];
    	if (socketSendBufferSizeHint > 0 || socketReceiveBufferSizeHint > 0) {
    		int bufSize = Math.max(socketSendBufferSizeHint, socketReceiveBufferSizeHint);
    		socketConfig = socketConfig
    				.setSndBufSize(bufSize)
    				.setRcvBufSize(bufSize);
        }
    	
        

        /* Accept Gzip response if configured */
        if (config.useGzip()) {
            httpClient = httpClient.addInterceptorLast(new HttpRequestInterceptor() {

                public void process(final HttpRequest request,
                        final HttpContext context) throws HttpException,
                        IOException {
                    if (!request.containsHeader("Accept-Encoding")) {
                        request.addHeader("Accept-Encoding", "gzip");
                    }
                }

            });

            httpClient = httpClient.addInterceptorLast(new HttpResponseInterceptor() {

                public void process(final HttpResponse response,
                        final HttpContext context) throws HttpException,
                        IOException {
                    HttpEntity entity = response.getEntity();
                    if (entity != null) {
                        Header ceheader = entity.getContentEncoding();
                        if (ceheader != null) {
                            HeaderElement[] codecs = ceheader.getElements();
                            for (int i = 0; i < codecs.length; i++) {
                                if (codecs[i].getName()
                                        .equalsIgnoreCase("gzip")) {
                                    response.setEntity(new GzipDecompressingEntity(
                                            response.getEntity()));
                                    return;
                                }
                            }
                        }
                    }
                }

            });
        }
        

        httpClient = httpClient
        		.setRetryHandler(HttpRequestNoRetryHandler.Singleton)
        		.setRedirectStrategy(new NeverFollowRedirectStrategy())
        		.setSSLSocketFactory(sslsf)
        		.setDefaultSocketConfig(socketConfig.build())
        		.setDefaultRequestConfig(requestConfig.build());

        return httpClient.build();
    }


    /**
     * Disable http redirect inside Apache HttpClient.
     */
    private static final class NeverFollowRedirectStrategy implements RedirectStrategy {

        @Override
        public boolean isRedirected(HttpRequest request, HttpResponse response,
                HttpContext context) throws ProtocolException {
            return false;
        }

        @Override
        public HttpUriRequest getRedirect(HttpRequest request,
                HttpResponse response, HttpContext context)
                throws ProtocolException {
            return null;
        }

    }

    /**
     * Simple implementation of SchemeSocketFactory (and
     * LayeredSchemeSocketFactory) that bypasses SSL certificate checks. This
     * class is only intended to be used for testing purposes.
     */
    private static class TrustingSocketFactory implements SchemeSocketFactory, SchemeLayeredSocketFactory {

        private SSLContext sslcontext = null;

        private static SSLContext createSSLContext() throws IOException {
            try {
                SSLContext context = SSLContext.getInstance("TLS");
                context.init(null, new TrustManager[] { new TrustingX509TrustManager() }, null);
                return context;
            } catch (Exception e) {
                throw new IOException(e.getMessage(), e);
            }
        }

        private SSLContext getSSLContext() throws IOException {
            if (this.sslcontext == null) this.sslcontext = createSSLContext();
            return this.sslcontext;
        }

        public Socket createSocket(HttpParams params) throws IOException {
            return getSSLContext().getSocketFactory().createSocket();
        }

        public Socket connectSocket(Socket sock,
                InetSocketAddress remoteAddress,
                InetSocketAddress localAddress, HttpParams params)
                throws IOException, UnknownHostException,
                ConnectTimeoutException {
            int connTimeout = HttpConnectionParams.getConnectionTimeout(params);
            int soTimeout = HttpConnectionParams.getSoTimeout(params);

            SSLSocket sslsock = (SSLSocket) ((sock != null) ? sock : createSocket(params));
            if (localAddress != null) sslsock.bind(localAddress);

            sslsock.connect(remoteAddress, connTimeout);
            sslsock.setSoTimeout(soTimeout);
            return sslsock;
        }

        public boolean isSecure(Socket sock) throws IllegalArgumentException {
            return true;
        }

        public Socket createLayeredSocket(Socket arg0, String arg1, int arg2, HttpParams arg3)
                throws IOException, UnknownHostException {
            return getSSLContext().getSocketFactory().createSocket(arg0, arg1, arg2, true);
        }
    }

    /**
     * Simple implementation of X509TrustManager that trusts all certificates.
     * This class is only intended to be used for testing purposes.
     */
    private static class TrustingX509TrustManager implements X509TrustManager {
        private static final X509Certificate[] X509_CERTIFICATES = new X509Certificate[0];

        public X509Certificate[] getAcceptedIssuers() {
            return X509_CERTIFICATES;
        }

        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            // No-op, to trust all certs
        }

        public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            // No-op, to trust all certs
        }
    };

    /**
     * HttpRequestInterceptor implementation to set up pre-emptive
     * authentication against a defined basic proxy server.
     */
    private static class PreemptiveProxyAuth implements HttpRequestInterceptor {
        private final HttpHost proxyHost;

        public PreemptiveProxyAuth(HttpHost proxyHost) {
            this.proxyHost = proxyHost;
        }

        public void process(HttpRequest request, HttpContext context) {
            AuthCache authCache;
            // Set up the a Basic Auth scheme scoped for the proxy - we don't
            // want to do this for non-proxy authentication.
            BasicScheme basicScheme = new BasicScheme(ChallengeState.PROXY);

            if (context.getAttribute(ClientContext.AUTH_CACHE) == null) {
                authCache = new BasicAuthCache();
                authCache.put(this.proxyHost, basicScheme);
                context.setAttribute(ClientContext.AUTH_CACHE, authCache);
            } else {
                authCache =
                    (AuthCache) context.getAttribute(ClientContext.AUTH_CACHE);
                authCache.put(this.proxyHost, basicScheme);
            }
        }
    }

}