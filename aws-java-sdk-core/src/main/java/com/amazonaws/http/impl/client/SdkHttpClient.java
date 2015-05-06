/*
 * Copyright 2010-2015 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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
package com.amazonaws.http.impl.client;

import org.apache.http.auth.AuthSchemeProvider;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.config.AuthSchemes;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.HttpClientConnectionManager;
import org.apache.http.impl.auth.BasicSchemeFactory;
import org.apache.http.impl.auth.DigestSchemeFactory;
import org.apache.http.impl.auth.win.WindowsCredentialsProvider;
import org.apache.http.impl.auth.win.WindowsNTLMSchemeFactory;
import org.apache.http.impl.auth.win.WindowsNegotiateSchemeFactory;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.SystemDefaultCredentialsProvider;
import org.apache.http.impl.client.WinHttpClients;
import com.amazonaws.http.conn.ClientConnectionManagerFactory;
import com.amazonaws.http.protocol.SdkHttpRequestExecutor;

public class SdkHttpClient {
    
    private static HttpClientBuilder createBuilder(HttpClientConnectionManager connectionManager) {
    	HttpClientBuilder builder;
    	if (WinHttpClients.isWinAuthAvailable()) {
            final Registry<AuthSchemeProvider> authSchemeRegistry = RegistryBuilder.<AuthSchemeProvider>create()
                    .register(AuthSchemes.BASIC, new BasicSchemeFactory())
                    .register(AuthSchemes.DIGEST, new DigestSchemeFactory())
                    .register(AuthSchemes.NTLM, new WindowsNTLMSchemeFactory(null))
                    .register(AuthSchemes.SPNEGO, new WindowsNegotiateSchemeFactory(null))
                    .build();
            final CredentialsProvider credsProvider = new WindowsCredentialsProvider(new SystemDefaultCredentialsProvider());
            builder = HttpClientBuilder.create()
                    .setDefaultCredentialsProvider(credsProvider)
                    .setDefaultAuthSchemeRegistry(authSchemeRegistry);
        } else {
            builder = HttpClientBuilder.create();
        }
    	
    	return builder
    			.setRequestExecutor(new SdkHttpRequestExecutor())
    			.setConnectionManager(ClientConnectionManagerFactory.wrap(connectionManager));
    }
    
    public static HttpClientBuilder custom(HttpClientConnectionManager connectionManager) {
    	return createBuilder(connectionManager);
    }
    
    public static CloseableHttpClient createDefault(HttpClientConnectionManager connectionManager) {
    	return createBuilder(connectionManager).build();
    }
    
    public static CloseableHttpClient createSystem(HttpClientConnectionManager connectionManager) {
    	return createBuilder(connectionManager).useSystemProperties().build();
    }
}
