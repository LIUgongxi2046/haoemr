package org.haoemr.security;

import jakarta.servlet.http.HttpServletRequest;

public interface EmergencyReauthenticationVerifier {
    void verify(HttpServletRequest request);
}
