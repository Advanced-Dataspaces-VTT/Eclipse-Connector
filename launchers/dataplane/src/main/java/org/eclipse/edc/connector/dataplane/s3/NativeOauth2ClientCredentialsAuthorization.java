/*
 * Copyright (c) 2026 Advanced Dataspaces VTT
 *
 * Licensed under the Apache License, Version 2.0.
 */

package org.eclipse.edc.connector.dataplane.s3;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jwt.SignedJWT;
import org.eclipse.dataplane.domain.Result;
import org.eclipse.dataplane.domain.registration.Authorization;
import org.eclipse.dataplane.domain.registration.AuthorizationProfile;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** OAuth2 client-credentials authorization with the IdentityHub audience parameter. */
final class NativeOauth2ClientCredentialsAuthorization implements Authorization {

    private static final String TYPE = "oauth2_client_credentials";

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Result<String> authorizationHeader(AuthorizationProfile profile) {
        try {
            var values = new LinkedHashMap<String, String>();
            values.put("grant_type", "client_credentials");
            values.put("client_id", required(profile, "clientId"));
            values.put("client_secret", required(profile, "clientSecret"));
            var audience = profile.stringAttribute("audience");
            if (audience != null && !audience.isBlank()) {
                values.put("audience", audience);
            }

            var form = values.entrySet().stream()
                    .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                    .reduce((left, right) -> left + "&" + right)
                    .orElseThrow();
            var request = HttpRequest.newBuilder(URI.create(required(profile, "tokenEndpoint")))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return Result.failure(new IllegalStateException("Token endpoint returned HTTP "
                        + response.statusCode() + ": " + response.body()));
            }
            var token = objectMapper.readTree(response.body()).path("access_token").asText(null);
            if (token == null || token.isBlank()) {
                return Result.failure(new IllegalStateException("Token endpoint response did not contain access_token"));
            }
            return Result.success("Bearer " + token);
        } catch (Exception error) {
            return Result.failure(error);
        }
    }

    @Override
    public Result<String> extractCallerId(String authorizationHeader) {
        try {
            var prefix = "Bearer ";
            if (authorizationHeader == null || !authorizationHeader.startsWith(prefix)) {
                return Result.failure(new IllegalArgumentException("Authorization header is not a Bearer token"));
            }
            var subject = SignedJWT.parse(authorizationHeader.substring(prefix.length()))
                    .getJWTClaimsSet().getSubject();
            if (subject == null || subject.isBlank()) {
                return Result.failure(new IllegalArgumentException("JWT does not contain a subject"));
            }
            return Result.success(subject);
        } catch (Exception error) {
            return Result.failure(error);
        }
    }

    private String required(AuthorizationProfile profile, String key) {
        var value = profile.stringAttribute(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Authorization profile is missing " + key);
        }
        return value;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
