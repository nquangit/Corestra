package dev.nquangit.corestra.access.template;

import dev.nquangit.corestra.flow.StoredRequestTemplate;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * Computes stable hashes for stored request templates.
 */
public final class RequestTemplateHasher {
    /**
     * Computes a SHA-256 hash over request method, target, headers, and body.
     *
     * @param request stored request template
     * @return lowercase hex SHA-256 hash
     */
    public String hash(StoredRequestTemplate request) {
        Objects.requireNonNull(request, "request");
        MessageDigest digest = sha256();
        updatePart(digest, request.method());
        updatePart(digest, request.scheme());
        updatePart(digest, request.host());
        updatePart(digest, String.valueOf(request.port()));
        updatePart(digest, request.pathAndQuery());
        updatePart(digest, String.valueOf(request.headerLines().size()));
        for (String header : request.headerLines()) {
            updatePart(digest, header);
        }
        updatePart(digest, request.body());
        return toHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static void updatePart(MessageDigest digest, String value) {
        byte[] bytes = Objects.requireNonNullElse(value, "").getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            hex.append(Character.forDigit((value >>> 4) & 0x0f, 16));
            hex.append(Character.forDigit(value & 0x0f, 16));
        }
        return hex.toString();
    }
}
