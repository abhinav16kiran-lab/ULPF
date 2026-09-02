package com.ulpf.controlplane.service;

import com.ulpf.common.JwtUtil;
import com.ulpf.common.db.UserRepository;
import com.ulpf.controlplane.model.Role;
import com.ulpf.controlplane.model.User;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;
    private final BCryptPasswordEncoder passwordEncoder;

    public AuthService(UserRepository userRepository, JwtUtil jwtUtil, BCryptPasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.jwtUtil = jwtUtil;
        this.passwordEncoder = passwordEncoder;
    }

    public String login(String username, String password) {
        Optional<User> userOpt = userRepository.findByUsername(username);

        if (userOpt.isEmpty()) {
            throw new BadCredentialsException("invalid credentials");
        }

        User user = userOpt.get();
        if (!passwordEncoder.matches(password, user.passwordHash())) {
            throw new BadCredentialsException("invalid credentials");
        }

        return jwtUtil.generateToken(user.userId(), user.username());
    }

    public void signUp(String username, String password) {
        if (userRepository.existsByUsername(username)) {
            throw new IllegalArgumentException("Username already exists");
        }

        String hashedPassword = passwordEncoder.encode(password);
        User newUser = new User(null, username, hashedPassword, Role.USER, null);
        userRepository.save(newUser);
    }
}