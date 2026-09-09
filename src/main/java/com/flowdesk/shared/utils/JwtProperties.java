package com.flowdesk.shared.utils;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "jwt")
@Validated
public class JwtProperties {

    @NotBlank
    private String secret;

    @Positive
    private long expiration;

    @NotBlank
    private String issuer;

    // getters / setters
    public String getSecret() { return secret; }
    public void setSecret(String secret) { this.secret = secret; }
    public long getExpiration() { return expiration; }
    public void setExpiration(long expiration) { this.expiration = expiration; }
    public String getIssuer() { return issuer; }
    public void setIssuer(String issuer) { this.issuer = issuer; }
}
