package com.itzixi.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.itzixi.bean.AppUser;
import com.itzixi.mapper.AppUserMapper;
import com.itzixi.service.AppUserService;
import jakarta.annotation.Resource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class AppUserServiceImpl implements AppUserService {

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Resource
    private AppUserMapper appUserMapper;

    @Override
    public AppUser register(String username, String password) {
        AppUser existing = findByUsername(username);
        if (existing != null) {
            return null;
        }

        AppUser user = new AppUser();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        appUserMapper.insert(user);
        return user;
    }

    @Override
    public AppUser authenticate(String username, String password) {
        AppUser user = findByUsername(username);
        if (user == null) {
            return null;
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            return null;
        }
        return user;
    }

    @Override
    public AppUser findById(String id) {
        return appUserMapper.selectById(id);
    }

    @Override
    public AppUser findByUsername(String username) {
        QueryWrapper<AppUser> query = new QueryWrapper<>();
        query.eq("username", username);
        return appUserMapper.selectOne(query);
    }
}
