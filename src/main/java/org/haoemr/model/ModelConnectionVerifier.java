package org.haoemr.model;

interface ModelConnectionVerifier {

    ProbeResult probe(String modelCode, String endpointUrl, String apiKeyReference);

    record ProbeResult(boolean succeeded, long latencyMs, String errorCode) {
        static ProbeResult ready(long latencyMs) {
            return new ProbeResult(true, latencyMs, null);
        }

        static ProbeResult failed(long latencyMs, String errorCode) {
            return new ProbeResult(false, latencyMs, errorCode);
        }
    }
}
