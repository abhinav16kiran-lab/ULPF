package com.ulpf.controlplane.service;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import com.ulpf.controlplane.model.Role;
import com.ulpf.controlplane.model.User;
import com.ulpf.controlplane.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class SignupService {

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    public SignupService(
            UserRepository userRepository,
            BCryptPasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public User signup(String username, String password) {

        if (userRepository.existsByUsername(username)) {
            throw new IllegalArgumentException("USERNAME_TAKEN");
        }

        String passwordHash = passwordEncoder.encode(password);

        User user = new User(
                null,
                username,
                passwordHash,
                Role.USER,
                LocalDateTime.now()
        );

        return userRepository.save(user);
    }

}