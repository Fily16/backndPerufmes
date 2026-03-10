package org.example.backendbvaberiaperfumes.controller;

import org.example.backendbvaberiaperfumes.config.JwtUtil;
import org.example.backendbvaberiaperfumes.dto.LoginRequest;
import org.example.backendbvaberiaperfumes.dto.LoginResponse;
import org.example.backendbvaberiaperfumes.model.Admin;
import org.example.backendbvaberiaperfumes.repository.AdminRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AdminRepository adminRepo;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public AuthController(AdminRepository adminRepo, PasswordEncoder passwordEncoder, JwtUtil jwtUtil) {
        this.adminRepo = adminRepo;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        Admin admin = adminRepo.findByEmail(request.getEmail())
                .orElse(null);

        if (admin == null || !passwordEncoder.matches(request.getPassword(), admin.getPassword())) {
            return ResponseEntity.status(401).body("Credenciales incorrectas");
        }

        String token = jwtUtil.generateToken(admin.getEmail());
        return ResponseEntity.ok(new LoginResponse(token, admin.getEmail(), admin.getName()));
    }
}
