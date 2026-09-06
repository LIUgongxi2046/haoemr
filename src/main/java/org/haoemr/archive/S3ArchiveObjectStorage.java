package org.haoemr.archive;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("prod")
final class S3ArchiveObjectStorage implements ArchiveObjectStorage {
    private static final int MAX_BYTES = 50 * 1024 * 1024;
    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withLocale(Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd")
            .withLocale(Locale.ROOT).withZone(ZoneOffset.UTC);

    private final URI endpoint;
    private final String bucket;
    private final String region;
    private final String accessKeyRef;
    private final String secretKeyRef;
    private final HttpClient http;

    S3ArchiveObjectStorage(
            @Value("${haoemr.production.storage.endpoint}") String endpoint,
            @Value("${haoemr.production.storage.bucket}") String bucket,
            @Value("${haoemr.production.storage.region:cn-north-1}") String region,
            @Value("${haoemr.production.storage.access-key-ref}") String accessKeyRef,
            @Value("${haoemr.production.storage.secret-key-ref}") String secretKeyRef) {
        this.endpoint = URI.create(endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint);
        this.bucket = requireSegment(bucket, "bucket");
        this.region = requireSegment(region, "region");
        this.accessKeyRef = accessKeyRef;
        this.secretKeyRef = secretKeyRef;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public String provider() { return "S3_COMPATIBLE"; }

    @Override
    public void putImmutable(String storageKey, byte[] content) {
        requireContent(content);
        HttpRequest request = signed("PUT", storageKey, "", content, "application/octet-stream", null)
                .header("If-None-Match", "*").build();
        HttpResponse<byte[]> response = send(request);
        if (response.statusCode() == 412 || response.statusCode() == 409) {
            throw failure("MEDICAL_RECORD_ASSET_OBJECT_EXISTS", 409, "Immutable object already exists");
        }
        requireSuccess(response, "MEDICAL_RECORD_ASSET_STORAGE_FAILED", "Asset object could not be stored");
    }

    @Override
    public byte[] read(String storageKey) {
        HttpResponse<byte[]> response = send(signed("GET", storageKey, "", new byte[0], null, null).build());
        requireSuccess(response, "MEDICAL_RECORD_ASSET_OBJECT_UNAVAILABLE", "Stored asset object is unavailable");
        requireContent(response.body());
        return response.body();
    }

    @Override
    public SealEvidence seal(String storageKey, Instant retainUntil) {
        String retain = DateTimeFormatter.ISO_INSTANT.format(retainUntil);
        byte[] body = ("<Retention xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Mode>COMPLIANCE</Mode>"
                + "<RetainUntilDate>" + retain + "</RetainUntilDate></Retention>")
                .getBytes(StandardCharsets.UTF_8);
        String md5 = base64Digest("MD5", body);
        HttpRequest request = signed("PUT", storageKey, "retention=", body, "application/xml", md5).build();
        HttpResponse<byte[]> response = send(request);
        requireSuccess(response, "MEDICAL_RECORD_ASSET_WORM_LOCK_FAILED", "S3 object-lock retention was rejected");

        HttpResponse<byte[]> evidence = send(signed("GET", storageKey, "retention=", new byte[0], null, null).build());
        requireSuccess(evidence, "MEDICAL_RECORD_ASSET_WORM_EVIDENCE_UNAVAILABLE",
                "S3 object-lock evidence could not be read back");
        String payload = new String(evidence.body(), StandardCharsets.UTF_8);
        if (!payload.contains("<Mode>COMPLIANCE</Mode>") || !payload.contains(retain)) {
            throw failure("MEDICAL_RECORD_ASSET_WORM_EVIDENCE_INVALID", 503,
                    "S3 object-lock evidence did not match requested compliance retention");
        }
        return new SealEvidence("S3_OBJECT_LOCK_COMPLIANCE", "s3://" + bucket + "/" + storageKey + "#" + retain);
    }

    @Override
    public void deleteUnsealedBestEffort(String storageKey) {
        try { send(signed("DELETE", storageKey, "", new byte[0], null, null).build()); }
        catch (RuntimeException ignored) { }
    }

    private HttpRequest.Builder signed(
            String method, String storageKey, String canonicalQuery, byte[] body, String contentType, String contentMd5) {
        String path = "/" + encode(bucket) + "/" + encodeKey(storageKey);
        URI uri = endpoint.resolve(path + (canonicalQuery.isEmpty() ? "" : "?retention"));
        Instant now = Instant.now();
        String amzDate = AMZ_DATE.format(now);
        String date = DATE.format(now);
        String payloadHash = sha256(body);
        String host = uri.getPort() < 0 ? uri.getHost() : uri.getHost() + ":" + uri.getPort();
        java.util.SortedMap<String, String> signedHeaders = new java.util.TreeMap<>();
        signedHeaders.put("host", host);
        if (contentMd5 != null) signedHeaders.put("content-md5", contentMd5);
        if (contentType != null) signedHeaders.put("content-type", contentType);
        signedHeaders.put("x-amz-content-sha256", payloadHash);
        signedHeaders.put("x-amz-date", amzDate);
        StringBuilder headers = new StringBuilder();
        signedHeaders.forEach((name, value) -> headers.append(name).append(':').append(value.trim()).append('\n'));
        String names = String.join(";", signedHeaders.keySet());
        String canonical = method + "\n" + path + "\n" + canonicalQuery + "\n" + headers + "\n" + names + "\n" + payloadHash;
        String scope = date + "/" + region + "/s3/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n"
                + sha256(canonical.getBytes(StandardCharsets.UTF_8));
        String accessKey = resolveSecret(accessKeyRef);
        String secretKey = resolveSecret(secretKeyRef);
        byte[] signingKey = hmac(hmac(hmac(hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), date), region), "s3"),
                "aws4_request");
        String signature = HexFormat.of().formatHex(hmac(signingKey, stringToSign));
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
                .header("x-amz-date", amzDate).header("x-amz-content-sha256", payloadHash)
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + scope
                        + ", SignedHeaders=" + names + ", Signature=" + signature);
        if (contentMd5 != null) request.header("Content-MD5", contentMd5);
        if (contentType != null) request.header("Content-Type", contentType);
        return request.method(method, body.length == 0 ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body));
    }

    private HttpResponse<byte[]> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw failure("MEDICAL_RECORD_ASSET_STORAGE_INTERRUPTED", 503, "Object storage request was interrupted");
        } catch (IOException unavailable) {
            throw failure("MEDICAL_RECORD_ASSET_STORAGE_UNAVAILABLE", 503, "Object storage is unavailable");
        }
    }

    private static void requireSuccess(HttpResponse<?> response, String code, String message) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) throw failure(code, 503, message);
    }

    private static void requireContent(byte[] content) {
        if (content == null || content.length < 1 || content.length > MAX_BYTES) {
            throw failure("MEDICAL_RECORD_ASSET_FILE_SIZE_INVALID", 422,
                    "Asset content must be between 1 byte and 50 MiB");
        }
    }

    private static String resolveSecret(String reference) {
        try {
            if (reference != null && reference.startsWith("env://")) {
                String value = System.getenv(reference.substring(6));
                if (value != null && !value.isBlank()) return value.trim();
            }
            if (reference != null && reference.startsWith("file://")) {
                String value = Files.readString(Path.of(URI.create(reference)), StandardCharsets.UTF_8).trim();
                if (!value.isBlank()) return value;
            }
        } catch (Exception ignored) { }
        throw failure("MEDICAL_RECORD_ASSET_STORAGE_CREDENTIAL_UNAVAILABLE", 503,
                "Object storage credential reference is unavailable");
    }

    private static String requireSegment(String value, String field) {
        if (value == null || !value.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalStateException("Invalid archive storage " + field);
        }
        return value;
    }

    private static String encodeKey(String value) {
        if (value == null || value.isBlank() || value.contains("..")) {
            throw failure("MEDICAL_RECORD_ASSET_STORAGE_KEY_INVALID", 400, "Storage key is invalid");
        }
        return java.util.Arrays.stream(value.split("/", -1)).map(S3ArchiveObjectStorage::encode)
                .collect(java.util.stream.Collectors.joining("/"));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private static String base64Digest(String algorithm, byte[] value) {
        try { return Base64.getEncoder().encodeToString(MessageDigest.getInstance(algorithm).digest(value)); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private static byte[] hmac(byte[] key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private static MedicalRecordAssetException failure(String code, int status, String message) {
        return new MedicalRecordAssetException(code, status, message);
    }
}
