package com.itzixi.security;

import com.itzixi.bean.AppUser;
import com.itzixi.mapper.AppUserMapper;
import com.itzixi.service.AppUserService;
import com.itzixi.utils.JwtUtil;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

/** 仅生产启动时启用。显式设置 bootstrap 密码才创建新管理员，不提升既有普通账号权限。 */
@Component
@Profile("prod")
public class AdminBootstrap implements ApplicationRunner {
    private final JwtUtil jwt;
    private final Environment env;
    private final AppUserService users;
    private final AppUserMapper mapper;
    public AdminBootstrap(JwtUtil jwt, Environment env, AppUserService users, AppUserMapper mapper) {
        this.jwt = jwt; this.env = env; this.users = users; this.mapper = mapper;
    }
    @jakarta.annotation.PostConstruct
    public void validateSecret() { jwt.validateProductionSecret(); }

    @Override public void run(ApplicationArguments args) {
        String password = env.getProperty("auth.admin.bootstrap-password", "");
        if (password.isEmpty()) return;
        String name = env.getProperty("auth.admin.username", "admin");
        if (!name.matches("[A-Za-z][A-Za-z0-9_]{1,7}")) throw new IllegalStateException("初始化管理员名需为 2–8 位字母数字下划线，以字母开头");
        AppUser existing = users.findByUsername(name);
        if (existing != null) {
            if (!existing.isAdministrator()) throw new IllegalStateException("管理员名称已被普通账号占用，请先明确处理该账号；不会自动提权");
            return; // 不能因重启而重置密码。
        }
        if (!password.equals(password.trim()) || password.length() < 12 || password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new IllegalStateException("初始化管理员密码不能含首尾空白，需至少 12 字符且不超过 72 字节");
        AppUser user = new AppUser(); user.setUsername(name); user.setAdministrator(true);
        user.setPasswordHash(new BCryptPasswordEncoder().encode(password));
        user.setCreatedAt(LocalDateTime.now()); user.setUpdatedAt(LocalDateTime.now());
        mapper.insert(user);
    }
}
