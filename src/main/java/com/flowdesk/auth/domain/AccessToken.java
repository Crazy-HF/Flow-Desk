package com.flowdesk.auth.domain;


import java.time.Instant;

/** 已签发的 Access Token 及其到期时刻。 */
public record AccessToken(String value, Instant expiresAt) {
}
