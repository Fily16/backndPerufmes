package org.example.backendbvaberiaperfumes.dto;

public class LoginResponse {
    private String token;
    private String email;
    private String name;

    public LoginResponse(String token, String email, String name) {
        this.token = token;
        this.email = email;
        this.name = name;
    }

    public String getToken() { return token; }
    public String getEmail() { return email; }
    public String getName() { return name; }
}
