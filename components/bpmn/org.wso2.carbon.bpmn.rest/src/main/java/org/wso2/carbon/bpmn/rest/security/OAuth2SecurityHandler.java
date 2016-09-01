/*
 * Copyright (c) 2016, WSO2 Inc. (http://wso2.com) All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.wso2.carbon.bpmn.rest.security;

import com.google.common.base.Charsets;
import com.google.common.io.ByteStreams;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.bpmn.rest.common.exception.RestApiOauthAuthenticationException;
import org.wso2.carbon.bpmn.rest.internal.RestServiceContentHolder;
import org.wso2.msf4j.Request;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.Map;

import javax.ws.rs.HttpMethod;

/**
 * Act as a security gateway for resources secured with Oauth2.
 * <p>
 * Verify Oauth2 access token in Authorization Bearer HTTP header and allow access to the resource accordingly.
 *
 * @since 1.0.0
 */
public class OAuth2SecurityHandler {
    private static final Logger log = LoggerFactory.getLogger(OAuth2SecurityHandler.class);

    private static final String AUTHORIZATION_HTTP_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "bearer";
    private static final String AUTH_SERVER_URL_KEY = "AUTH_SERVER_URL";
    private static final String AUTH_SERVER_URL;
    private static final String TRUST_STORE = "TRUST_STORE";
    private static final String TRUST_STORE_PASSWORD = "TRUST_STORE_PASSWORD";

    static {
        AUTH_SERVER_URL = RestServiceContentHolder.getInstance().getRestService().getBPMNEngineService()
                .getProcessEngineConfiguration().getAuthServerUrl();
        if (AUTH_SERVER_URL == null) {
            throw new RuntimeException(AUTH_SERVER_URL_KEY + " is not specified in bps config.");
        }
        String trustStore = System.getProperty(TRUST_STORE, null);
        String trustStorePassword = System.getProperty(TRUST_STORE_PASSWORD, null);
        if (trustStore != null && !trustStore.isEmpty() && trustStorePassword != null && !trustStorePassword
                .isEmpty()) {
            System.setProperty("javax.net.ssl.trustStore", trustStore);
            System.setProperty("javax.net.ssl.trustStorePassword", trustStorePassword);
        }
    }

    /**
     * Authenticate the token against the authorization server
     * @param request HTTP request
     * @return username if authenticated, else null
     * @throws Exception
     */
    public String getAuthenticatedUser(Request request)
            throws Exception {

        Map<String, String> headers = request.getHeaders();
        if (headers != null && headers.containsKey(AUTHORIZATION_HTTP_HEADER)) {
            String authHeader = headers.get(AUTHORIZATION_HTTP_HEADER);
            return validateToken(authHeader);
        } else {
            throw new RestApiOauthAuthenticationException("Missing Authorization header in the request.`");
        }
    }

    /**
     * Extract the accessToken from the give Authorization header value and validates the accessToken
     * with an external key manager.
     *
     * @param authHeader Authorization Bearer header which contains the access token
     * @return Username of the validated user or null
     */
    private String validateToken(String authHeader) throws Exception {
        // 1. Check whether this token is bearer token, if not return false
        String accessToken = extractAccessToken(authHeader);

        // 2. Send a request to key server's introspect endpoint to validate this token
        String responseStr = getValidatedTokenResponse(accessToken);
        Map<String, String> responseData = getResponseDataMap(responseStr);

        if (responseData != null) {
            // 3. Process the response and return true if the token is valid.
            if (!Boolean.parseBoolean(String.valueOf(responseData.get(IntrospectionResponse.ACTIVE)))) {
                throw new RestApiOauthAuthenticationException("Invalid Access token.");
            } else {
                return responseData.get(IntrospectionResponse.USERNAME);
            }
        } else {
            throw new RuntimeException("Error reading response from authorization server.");
        }
    }

    /**
     * @param authHeader Authorization Bearer header which contains the access token
     * @return access token
     */
    private String extractAccessToken(String authHeader) throws RestApiOauthAuthenticationException {
        authHeader = authHeader.trim();
        if (authHeader.toLowerCase(Locale.US).startsWith(BEARER_PREFIX)) {
            // Split the auth header to get the access token.
            // Value should be in this format ("Bearer" 1*SP b64token)
            String[] authHeaderParts = authHeader.split(" ");
            if (authHeaderParts.length == 2) {
                return authHeaderParts[1];
            }
        }

        throw new RestApiOauthAuthenticationException("Invalid Authorization header: " + authHeader);
    }

    /**
     * Validated the given accessToken with an external key server.
     *
     * @param accessToken AccessToken to be validated.
     * @return the response from the key manager server.
     */
    private String getValidatedTokenResponse(String accessToken) throws IOException {
        URL url;
        try {
            url = new URL(AUTH_SERVER_URL);
            HttpURLConnection urlConn = (HttpURLConnection) url.openConnection();
            urlConn.setDoOutput(true);
            urlConn.setRequestMethod(HttpMethod.POST);
            urlConn.getOutputStream().write(("token=" + accessToken).getBytes(Charsets.UTF_8));
            return new String(ByteStreams.toByteArray(urlConn.getInputStream()), Charsets.UTF_8);
        } catch (java.io.IOException e) {
            log.error("Error invoking Authorization Server", e);
            throw new IOException("Error invoking Authorization Server", e);
        }
    }

    /**
     * @param responseStr validated token response string returned from the key server.
     * @return a Map of key, value pairs available the response String.
     */
    private Map<String, String> getResponseDataMap(String responseStr) {
        Gson gson = new Gson();
        Type typeOfMapOfStrings = TypeToken.get(Map.class).getType();
        return gson.fromJson(responseStr, typeOfMapOfStrings);
    }
}
