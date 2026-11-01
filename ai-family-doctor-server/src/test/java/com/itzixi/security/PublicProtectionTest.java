package com.itzixi.security;

import com.itzixi.bean.AppUser;
import com.itzixi.service.AppUserService;
import com.itzixi.utils.AuthHelper;
import com.itzixi.utils.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublicProtectionTest {
    @Test void deletedAccountCannotUseAnOtherwiseValidToken() {
        JwtUtil jwt = mock(JwtUtil.class);
        AppUserService users = mock(AppUserService.class);
        AuthHelper helper = new AuthHelper(jwt, users);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer token");
        when(jwt.isValid("token")).thenReturn(true);
        when(jwt.getUserId("token")).thenReturn("old-id");
        assertThat(helper.requireUsername(request)).isNull();
        AppUser replacement = new AppUser(); replacement.setId("new-id"); replacement.setUsername("alice");
        when(users.findByUsername("alice")).thenReturn(replacement);
        assertThat(helper.requireUsername(request)).isNull();
    }
    @Test void identityComesFromExistingAccountNotTokenUsername() {
        JwtUtil jwt = mock(JwtUtil.class); AppUserService users = mock(AppUserService.class);
        AppUser user = new AppUser(); user.setId("id"); user.setUsername("current");
        when(jwt.isValid("token")).thenReturn(true); when(jwt.getUserId("token")).thenReturn("id");
        when(users.findById("id")).thenReturn(user);
        var request = new MockHttpServletRequest(); request.addHeader("Authorization", "Bearer token");
        assertThat(new AuthHelper(jwt, users).requireUsername(request)).isEqualTo("current");
    }
    @Test void quotaRejectsOverflowAndRecoversAfterWindow() {
        var start = Instant.parse("2026-11-01T00:00:00Z");
        var quota = new RequestQuota(Clock.fixed(start, ZoneOffset.UTC), 2);
        assertThat(quota.take("alice", 2, 60)).isZero();
        assertThat(quota.take("alice", 2, 60)).isZero();
        assertThat(quota.take("alice", 2, 60)).isEqualTo(60);
        assertThat(quota.take("bob", 1, 60)).isZero();
        assertThat(quota.take("third", 1, 60)).isPositive();
        quota.clock = Clock.fixed(start.plusSeconds(60), ZoneOffset.UTC);
        assertThat(quota.take("alice", 2, 60)).isZero();
    }
    @Test void weakProductionSecretIsRejected() {
        var jwt = new JwtUtil(); ReflectionTestUtils.setField(jwt,"secret","change-this-secret-in-prod");
        assertThatThrownBy(jwt::validateProductionSecret).isInstanceOf(IllegalStateException.class);
        ReflectionTestUtils.setField(jwt,"secret","0123456789abcdef".repeat(4));
        assertThatCode(jwt::validateProductionSecret).doesNotThrowAnyException();
    }
}
