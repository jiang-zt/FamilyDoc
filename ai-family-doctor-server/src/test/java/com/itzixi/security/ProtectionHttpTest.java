package com.itzixi.security;

import com.itzixi.controller.AuthController;
import com.itzixi.controller.ChatController;
import com.itzixi.utils.AuthHelper;
import com.itzixi.utils.JwtUtil;
import com.itzixi.bean.AppUser;
import com.itzixi.service.AppUserService;
import com.itzixi.service.impl.AppUserServiceImpl;
import com.itzixi.mapper.AppUserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ProtectionHttpTest {
    @Test void syncAndStreamCannotBypassSharedUserQuota() throws Exception {
        var auth = mock(AuthHelper.class); when(auth.requireUsername(any())).thenReturn("alice");
        var env = new MockEnvironment().withProperty("protection.chat-user-per-minute","1");
        var protection = new PublicProtection(env, auth);
        var controller = new ChatController(); ReflectionTestUtils.setField(controller,"authHelper",auth);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).addInterceptors(protection).build();
        // 无 body 的首请求也会在后续参数校验阶段失败，但已经消耗尝试额度。
        mvc.perform(post("/chat")).andExpect(status().isBadRequest());
        mvc.perform(post("/chat/stream")).andExpect(status().isTooManyRequests())
            .andExpect(header().exists("Retry-After"));
    }
    @Test void unauthenticatedChatIsRejectedBeforeQuota() throws Exception {
        var auth = mock(AuthHelper.class);
        var mvc = MockMvcBuilders.standaloneSetup(new ChatController())
            .addInterceptors(new PublicProtection(new MockEnvironment(),auth)).build();
        mvc.perform(post("/chat")).andExpect(status().isUnauthorized());
    }
    @Test void usernameAloneDoesNotGrantAdministratorRights() throws Exception {
        var auth = mock(AuthHelper.class); when(auth.requireUsername(any())).thenReturn("admin");
        var users = mock(AppUserService.class); var ordinary = new AppUser(); ordinary.setUsername("admin");
        when(users.findByUsername("admin")).thenReturn(ordinary);
        var controller = new AuthController(); ReflectionTestUtils.setField(controller,"authHelper",auth);
        ReflectionTestUtils.setField(controller,"appUserService",users);
        var mvc=MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(get("/auth/users")).andExpect(status().isForbidden());
        ordinary.setAdministrator(true);
        when(users.listAll()).thenReturn(java.util.List.of());
        mvc.perform(get("/auth/users")).andExpect(status().isOk());
    }
    @Test void publicRegistrationRejectsAdminAndConcurrentDuplicateReturnsConflict() throws Exception {
        var mapper = mock(AppUserMapper.class); var users = new AppUserServiceImpl();
        ReflectionTestUtils.setField(users,"appUserMapper",mapper);
        assertThatThrownBy(()->users.register("AdMiN","validPassword123"))
            .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(mapper);
        when(mapper.insert(any(AppUser.class))).thenThrow(new DuplicateKeyException("duplicate"));
        var controller = new AuthController(); ReflectionTestUtils.setField(controller,"appUserService",users);
        var mvc=MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(post("/auth/register").contentType("application/json")
            .content("{\"username\":\"alice\",\"password\":\"validPassword123\"}"))
            .andExpect(status().isConflict());
    }
    @Test void administratorBootstrapNeverPromotesAnExistingOrdinaryAccount() {
        var users=mock(AppUserService.class); var mapper=mock(AppUserMapper.class);
        var ordinary=new AppUser(); ordinary.setUsername("admin");
        when(users.findByUsername("admin")).thenReturn(ordinary);
        var env=new MockEnvironment().withProperty("auth.admin.bootstrap-password","longPassword123");
        var bootstrap=new AdminBootstrap(mock(JwtUtil.class),env,users,mapper);
        assertThatThrownBy(()->bootstrap.run(null)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(mapper);
    }
    @Test void administratorBootstrapRejectsWhitespacePasswordThatLoginWouldTrim() {
        var users=mock(AppUserService.class); var mapper=mock(AppUserMapper.class);
        var env=new MockEnvironment().withProperty("auth.admin.bootstrap-password","  longPassword123");
        var bootstrap=new AdminBootstrap(mock(JwtUtil.class),env,users,mapper);
        assertThatThrownBy(()->bootstrap.run(null)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(mapper);
    }
    @Test void globalConcurrencyLimitRecoversWithoutPermitLeak() {
        var guard=new com.itzixi.service.ChatGenerationGuard();
        ReflectionTestUtils.setField(guard,"maxConcurrent",2);
        assertThat(guard.tryAcquire("a")).isTrue(); assertThat(guard.tryAcquire("b")).isTrue();
        assertThat(guard.tryAcquire("c")).isFalse(); guard.release("a");
        assertThat(guard.tryAcquire("c")).isTrue(); guard.release("a");
        assertThat(guard.tryAcquire("d")).isFalse();
    }
}
