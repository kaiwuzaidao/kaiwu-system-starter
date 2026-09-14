package com.kaiwu.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jsonwebtoken.Jwts;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GatewayContextVerifierTest {

    private KeyPair keyPair;
    private KaiwuStarterProperties properties;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
        properties = new KaiwuStarterProperties();
        properties.setContextPublicKey(pem(keyPair));
        properties.setAudience("kaiwu-demo-service");
        properties.setProjectCode("demo");
    }

    @Test
    void verifiesAudienceProjectAndPermissions() {
        GatewayContextVerifier verifier = new GatewayContextVerifier(properties);

        KaiwuContext context = verifier.verify(token("kaiwu-demo-service", "demo"));

        assertThat(context.userId()).isEqualTo("1900000000000000001");
        assertThat(context.projectId()).isEqualTo("1900000000000000100");
        assertThat(context.hasPermission("demo:record:list")).isTrue();
    }

    @Test
    void rejectsWrongAudience() {
        GatewayContextVerifier verifier = new GatewayContextVerifier(properties);

        assertThatThrownBy(() -> verifier.verify(token("other-service", "demo")))
                .isInstanceOf(ContextVerificationException.class)
                .hasMessageContaining("audience");
    }

    @Test
    void rejectsWrongProject() {
        GatewayContextVerifier verifier = new GatewayContextVerifier(properties);

        assertThatThrownBy(() -> verifier.verify(token("kaiwu-demo-service", "other")))
                .isInstanceOf(ContextVerificationException.class)
                .hasMessageContaining("项目编码");
    }

    @Test
    void rejectsExpiredContext() {
        GatewayContextVerifier verifier = new GatewayContextVerifier(properties);

        assertThatThrownBy(() -> verifier.verify(token(
                        "kaiwu-demo-service",
                        "demo",
                        Instant.now().minusSeconds(120),
                        Instant.now().minusSeconds(60))))
                .isInstanceOf(ContextVerificationException.class);
    }

    private String token(String audience, String projectCode) {
        Instant now = Instant.now();
        return token(audience, projectCode, now, now.plusSeconds(60));
    }

    private String token(String audience, String projectCode, Instant issuedAt, Instant expiresAt) {
        return Jwts.builder()
                .header()
                .type(GatewayContextVerifier.CONTEXT_TYPE)
                .and()
                .issuer("kaiwu-gateway-service")
                .audience()
                .add(audience)
                .and()
                .subject("1900000000000000001")
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .claim("sid", "session-1")
                .claim("projectId", "1900000000000000100")
                .claim("projectCode", projectCode)
                .claim("permissions", List.of("demo:record:list"))
                .claim("projectRoleCodes", List.of("project_admin"))
                .claim("authzVersion", "v1")
                .signWith(keyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }

    private static String pem(KeyPair pair) {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes())
                        .encodeToString(pair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----";
    }
}
