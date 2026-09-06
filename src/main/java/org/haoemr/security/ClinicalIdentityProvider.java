package org.haoemr.security;

import jakarta.servlet.http.HttpServletRequest;

interface ClinicalIdentityProvider {

    ClinicalIdentity current(HttpServletRequest request);
}
