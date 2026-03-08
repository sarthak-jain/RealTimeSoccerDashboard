package com.soccerdashboard.service;

import com.soccerdashboard.dto.AuthResponse;
import com.soccerdashboard.dto.LoginRequest;
import com.soccerdashboard.dto.SignupRequest;
import com.soccerdashboard.model.User;
import com.soccerdashboard.repository.UserRepository;
import com.soccerdashboard.security.JwtTokenProvider;
import com.soccerdashboard.workflow.WorkflowTracer;
import com.soccerdashboard.workflow.WorkflowStep;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final WorkflowTracer workflowTracer;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       JwtTokenProvider tokenProvider, WorkflowTracer workflowTracer) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
        this.workflowTracer = workflowTracer;
    }

    public AuthResponse signup(SignupRequest request) {
        WorkflowTracer.Trace trace = workflowTracer.startUserAction("Signup: " + request.getUsername());

        trace.emitApiGateway("POST /api/auth/signup");

        if (userRepository.existsByEmail(request.getEmail())) {
            trace.emitError("Validation", "Email already in use", 0);
            throw new RuntimeException("Email already in use");
        }
        if (userRepository.existsByUsername(request.getUsername())) {
            trace.emitError("Validation", "Username already taken", 0);
            throw new RuntimeException("Username already taken");
        }

        long dbStart = System.nanoTime();
        User user = new User();
        user.setEmail(request.getEmail());
        user.setUsername(request.getUsername());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        userRepository.save(user);
        long dbMs = (System.nanoTime() - dbStart) / 1_000_000;

        trace.emitDbWrite("users", "INSERT new user: " + user.getUsername(), dbMs);

        String token = tokenProvider.generateToken(user.getUsername());
        trace.emitAuthCheck(user.getUsername(), tokenProvider.getExpirationMs() / 3600000 + "h remaining");
        trace.emitResponse(201, 1);

        return new AuthResponse(token, user.getUsername(), user.getEmail());
    }

    public AuthResponse login(LoginRequest request) {
        WorkflowTracer.Trace trace = workflowTracer.startUserAction("Login: " + request.getEmail());

        trace.emitApiGateway("POST /api/auth/login");

        long dbStart = System.nanoTime();
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> {
                    trace.emitError("Auth", "User not found", 0);
                    return new RuntimeException("Invalid credentials");
                });
        long dbMs = (System.nanoTime() - dbStart) / 1_000_000;

        trace.emitDbRead("users", "SELECT user by email", dbMs);

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            trace.emitError("Auth", "Invalid password", 0);
            throw new RuntimeException("Invalid credentials");
        }

        user.setLastLogin(LocalDateTime.now());
        userRepository.save(user);

        String token = tokenProvider.generateToken(user.getUsername());
        trace.emitAuthCheck(user.getUsername(), tokenProvider.getExpirationMs() / 3600000 + "h remaining");
        trace.emitResponse(200, 1);

        return new AuthResponse(token, user.getUsername(), user.getEmail());
    }
}
