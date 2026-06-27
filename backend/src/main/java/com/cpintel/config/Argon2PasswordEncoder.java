package com.cpintel.config;

import de.mkammerer.argon2.Argon2;
import de.mkammerer.argon2.Argon2Factory;
import org.springframework.security.crypto.password.PasswordEncoder;

public class Argon2PasswordEncoder implements PasswordEncoder {

    private final Argon2 argon2 = Argon2Factory.createAdvanced(Argon2Factory.Argon2Types.ARGON2id);

    private static final int ITERATIONS  = 3;
    private static final int MEMORY_KB   = 65536;
    private static final int PARALLELISM = 2;

    @Override
    public String encode(CharSequence rawPassword) {
        return argon2.hash(ITERATIONS, MEMORY_KB, PARALLELISM, rawPassword.toString().toCharArray());
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        return argon2.verify(encodedPassword, rawPassword.toString().toCharArray());
    }
}
