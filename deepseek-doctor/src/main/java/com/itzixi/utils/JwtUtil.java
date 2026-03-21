package com.itzixi.utils;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Date;

@Component
public class JwtUtil {

    @Value("${auth.jwt.secret}")
    private String secret;

    @Value("${auth.jwt.expire-minutes}")
    private long expireMinutes;

    public String createToken(String userId, String username) {
        Algorithm algorithm = Algorithm.HMAC256(secret);
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(expireMinutes * 60);
        return JWT.create()
                .withSubject(username)
                .withClaim("uid", userId)
                .withIssuedAt(Date.from(now))
                .withExpiresAt(Date.from(exp))
                .sign(algorithm);
    }

    public DecodedJWT verify(String token) {
        Algorithm algorithm = Algorithm.HMAC256(secret);
        JWTVerifier verifier = JWT.require(algorithm).build();
        return verifier.verify(token);
    }

    public String getUsername(String token) {
        DecodedJWT jwt = verify(token);
        return jwt.getSubject();
    }

    public String getUserId(String token) {
        DecodedJWT jwt = verify(token);
        return jwt.getClaim("uid").asString();
    }

    public long getExpiresAt(String token) {
        DecodedJWT jwt = verify(token);
        return jwt.getExpiresAt().getTime();
    }

    public boolean isValid(String token) {
        try {
            verify(token);
            return true;
        } catch (JWTVerificationException e) {
            return false;
        }
    }
}
