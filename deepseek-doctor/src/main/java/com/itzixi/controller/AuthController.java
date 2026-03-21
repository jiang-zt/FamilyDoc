package com.itzixi.controller;

import com.itzixi.bean.AppUser;
import com.itzixi.bean.AuthLoginRequest;
import com.itzixi.bean.AuthRegisterRequest;
import com.itzixi.bean.AuthResponse;
import com.itzixi.bean.AuthUserView;
import com.itzixi.service.AppUserService;
import com.itzixi.utils.AuthHelper;
import com.itzixi.utils.JwtUtil;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("auth")
public class AuthController {

    @Resource
    private AppUserService appUserService;

    @Resource
    private JwtUtil jwtUtil;

    @Resource
    private AuthHelper authHelper;

    @PostMapping("/register")
    public AuthResponse register(@RequestBody AuthRegisterRequest request) {
        if (request.getUsername() == null || request.getUsername().trim().isEmpty()
                || request.getPassword() == null || request.getPassword().trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户名或密码不能为空");
        }

        AppUser user = appUserService.register(request.getUsername().trim(), request.getPassword().trim());
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "用户名已存在");
        }
        return buildAuthResponse(user);
    }

    @PostMapping("/login")
    public AuthResponse login(@RequestBody AuthLoginRequest request) {
        if (request.getUsername() == null || request.getUsername().trim().isEmpty()
                || request.getPassword() == null || request.getPassword().trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户名或密码不能为空");
        }
        AppUser user = appUserService.authenticate(request.getUsername().trim(), request.getPassword().trim());
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }
        return buildAuthResponse(user);
    }

    @GetMapping("/me")
    public AuthUserView me(HttpServletRequest request) {
        String username = authHelper.requireUsername(request);
        if (username == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        AppUser user = appUserService.findByUsername(username);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        return toUserView(user);
    }

    private AuthResponse buildAuthResponse(AppUser user) {
        String token = jwtUtil.createToken(user.getId(), user.getUsername());
        AuthResponse response = new AuthResponse();
        response.setToken(token);
        response.setExpiresAt(jwtUtil.getExpiresAt(token));
        response.setUser(toUserView(user));
        return response;
    }

    private AuthUserView toUserView(AppUser user) {
        AuthUserView view = new AuthUserView();
        view.setId(user.getId());
        view.setUsername(user.getUsername());
        return view;
    }
}
