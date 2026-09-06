package org.haoemr.knowledge;

final class PathwayKnowledgeException extends RuntimeException {

    private final String code;
    private final int status;

    PathwayKnowledgeException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
