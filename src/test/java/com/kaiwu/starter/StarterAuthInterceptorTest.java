package com.kaiwu.starter;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaiwu.starter.logging.HttpAccessLogFilter;
import io.jsonwebtoken.Jwts;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

class StarterAuthInterceptorTest {

    @Test
    void rejectsAuthenticatedContextWithoutRequiredPermission() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        KaiwuStarterProperties properties = new KaiwuStarterProperties();
        properties.setContextPublicKey(pem(keyPair));
        properties.setAudience("kaiwu-system-service");
        GatewayContextVerifier verifier = new GatewayContextVerifier(properties);
        StarterAuthInterceptor interceptor = new StarterAuthInterceptor(verifier, new ContextPermissionResolver());

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(StarterAuthInterceptor.CONTEXT_HEADER, token(keyPair));
        MockHttpServletResponse response = new MockHttpServletResponse();
        HandlerMethod handler = new HandlerMethod(new ProtectedHandler(), "read");

        boolean allowed = interceptor.preHandle(request, response, handler);

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("无操作权限");
    }

    @Test
    void exposesOnlyVerifiedIdentityToAccessLogAndClearsMdc() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        KaiwuStarterProperties properties = new KaiwuStarterProperties();
        properties.setContextPublicKey(pem(keyPair));
        properties.setAudience("kaiwu-system-service");
        StarterAuthInterceptor interceptor =
                new StarterAuthInterceptor(new GatewayContextVerifier(properties), new ContextPermissionResolver());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(StarterAuthInterceptor.CONTEXT_HEADER, token(keyPair, List.of("system:platform:read")));
        MockHttpServletResponse response = new MockHttpServletResponse();
        HandlerMethod handler = new HandlerMethod(new ProtectedHandler(), "read");

        assertThat(interceptor.preHandle(request, response, handler)).isTrue();
        assertThat(request.getAttribute(HttpAccessLogFilter.USER_ID_ATTRIBUTE)).isEqualTo("1");
        assertThat(MDC.get("userId")).isEqualTo("1");

        interceptor.afterCompletion(request, response, handler, null);
        assertThat(StarterContext.current()).isNull();
        assertThat(MDC.get("userId")).isNull();
    }

    private static String token(KeyPair keyPair) {
        return token(keyPair, List.of("other:permission:read"));
    }

    private static String token(KeyPair keyPair, List<String> permissions) {
        Instant now = Instant.now();
        return Jwts.builder()
                .header()
                .type(GatewayContextVerifier.CONTEXT_TYPE)
                .and()
                .issuer("kaiwu-gateway-service")
                .audience()
                .add("kaiwu-system-service")
                .and()
                .subject("1")
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(60)))
                .claim("sid", "session-1")
                .claim("permissions", permissions)
                .signWith(keyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }

    private static String pem(KeyPair pair) {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes())
                        .encodeToString(pair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----";
    }

    static final class ProtectedHandler {

        @RequirePermission("system:platform:read")
        public void read() {}
    }
}
