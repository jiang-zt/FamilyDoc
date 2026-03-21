package com.itzixi.service;

import com.itzixi.bean.AppUser;

public interface AppUserService {

    AppUser register(String username, String password);

    AppUser authenticate(String username, String password);

    AppUser findById(String id);

    AppUser findByUsername(String username);
}
