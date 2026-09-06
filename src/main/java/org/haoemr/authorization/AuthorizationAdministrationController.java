package org.haoemr.authorization;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.authorization.AuthorizationAdministrationService.AuthorizationPolicyWire;
import org.haoemr.authorization.AuthorizationAdministrationService.AuthorizationSimulationRequest;
import org.haoemr.authorization.AuthorizationAdministrationService.EmergencyAccessGrantWire;
import org.haoemr.authorization.AuthorizationAdministrationService.EmergencyAccessRequest;
import org.haoemr.authorization.AuthorizationAdministrationService.EmergencyReviewRequest;
import org.haoemr.authorization.AuthorizationAdministrationService.PolicyCreateRequest;
import org.haoemr.authorization.AuthorizationAdministrationService.PolicyPublishRequest;
import org.haoemr.security.AuthorizationDecisionService.AuthorizationDecision;
import org.haoemr.security.ClinicalCommandSecurity;
import org.haoemr.security.ClinicalIdentity;
import org.haoemr.security.EmergencyReauthenticationVerifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
final class AuthorizationAdministrationController {
    private final ClinicalCommandSecurity security;
    private final AuthorizationAdministrationService authorization;
    private final EmergencyReauthenticationVerifier reauthentication;

    AuthorizationAdministrationController(ClinicalCommandSecurity security,
            AuthorizationAdministrationService authorization,
            EmergencyReauthenticationVerifier reauthentication) {
        this.security = security;
        this.authorization = authorization;
        this.reauthentication = reauthentication;
    }

    @GetMapping("/admin/access-policies")
    List<AuthorizationPolicyWire> listPolicies(HttpServletRequest request) {
        return authorization.listPolicies(security.authenticate(request));
    }

    @PostMapping("/admin/access-policies")
    ResponseEntity<AuthorizationPolicyWire> createPolicy(HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey, @RequestBody PolicyCreateRequest body) {
        return ResponseEntity.status(201).body(authorization.createPolicy(
                security.authenticate(request), idempotencyKey, body));
    }

    @PostMapping("/admin/access-policies/{policyId}/publish")
    AuthorizationPolicyWire publishPolicy(HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @PathVariable UUID policyId, @RequestBody PolicyPublishRequest body) {
        return authorization.publishPolicy(security.authenticate(request), idempotencyKey, policyId, body);
    }

    @PostMapping("/admin/access-simulations")
    AuthorizationDecision simulate(HttpServletRequest request, @RequestBody AuthorizationSimulationRequest body) {
        return authorization.simulate(security.authenticate(request), body);
    }

    @PostMapping("/emergency-access-grants")
    ResponseEntity<EmergencyAccessGrantWire> requestEmergency(HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey, @RequestBody EmergencyAccessRequest body) {
        reauthentication.verify(request);
        ClinicalIdentity identity = security.authenticate(request);
        return ResponseEntity.status(201).body(authorization.requestEmergency(identity, idempotencyKey, body));
    }

    @GetMapping("/emergency-access-grants")
    List<EmergencyAccessGrantWire> listOwnEmergency(HttpServletRequest request) {
        return authorization.listOwnEmergency(security.authenticate(request));
    }

    @GetMapping("/admin/emergency-access-grants")
    List<EmergencyAccessGrantWire> listEmergencyForReview(HttpServletRequest request) {
        return authorization.listEmergencyForReview(security.authenticate(request));
    }

    @PostMapping("/admin/emergency-access-grants/{grantId}/reviews")
    EmergencyAccessGrantWire reviewEmergency(HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @PathVariable UUID grantId, @RequestBody EmergencyReviewRequest body) {
        return authorization.reviewEmergency(security.authenticate(request), idempotencyKey, grantId, body);
    }
}
