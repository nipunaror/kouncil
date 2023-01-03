package com.consdata.kouncil.config.security;

import java.io.IOException;

public interface UserManager {

    boolean firstTimeLogin();

    default void skipChangeDefaultPassword() throws IOException {
    }

    default void changeDefaultPassword(String password) throws IOException {
    }
}
