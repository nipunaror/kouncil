package com.consdata.kouncil.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@Service
@Profile("in-memory")
@RequiredArgsConstructor
public class InMemoryUserManager implements UserManager {

    private static final String ADMIN_CONFIG = "default_admin_password.txt";
    private final UserDetailsManager userDetailsManager;

    @GetMapping("/api/firstTimeLogin")
    public boolean firstTimeLogin() {
        Path path = Paths.get(ADMIN_CONFIG);
        return !Files.exists(path);
    }

    @Override
    @GetMapping("/api/skipChangeDefaultPassword")
    public void skipChangeDefaultPassword() throws IOException {
        Path path = Paths.get(ADMIN_CONFIG);
        byte[] strToBytes = "admin".getBytes();
        Files.write(path, strToBytes);
    }

    @Override
    @PostMapping("/api/changeDefaultPassword")
    public void changeDefaultPassword(@RequestBody String password) throws IOException {
        Path path = Paths.get(ADMIN_CONFIG);
        String oldPassword;
        if (!Files.exists(path)) {
            oldPassword = "admin";
        } else {
            oldPassword = Files.readString(path);
        }
        this.userDetailsManager.changePassword(oldPassword, String.format("{noop}%s", password));
        byte[] strToBytes = password.getBytes();
        Files.write(path, strToBytes);
    }

}
