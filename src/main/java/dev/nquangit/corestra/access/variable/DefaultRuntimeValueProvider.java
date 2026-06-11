package dev.nquangit.corestra.access.variable;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Default runtime value generator for built-in runtime variables.
 */
public final class DefaultRuntimeValueProvider implements RuntimeValueProvider {
    private static final char[] ALPHANUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    private static final int NONCE_BYTES = 16;
    private static final int RANDOM_ALNUM_LENGTH = 16;

    private final Clock clock;
    private final SecureRandom secureRandom;

    /**
     * Creates a provider backed by {@link Clock#systemUTC()} and {@link SecureRandom}.
     */
    public DefaultRuntimeValueProvider() {
        this(Clock.systemUTC(), new SecureRandom());
    }

    /**
     * Creates a provider with injectable dependencies for deterministic tests.
     *
     * @param clock the clock used for runtime timestamps
     * @param secureRandom the random source used for generated values
     */
    public DefaultRuntimeValueProvider(Clock clock, SecureRandom secureRandom) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
    }

    /**
     * Resolves a built-in runtime key.
     *
     * @param key the key inside the runtime scope
     * @return the generated value, or empty when unsupported
     */
    @Override
    public Optional<String> resolve(String key) {
        Objects.requireNonNull(key, "key");
        return switch (key) {
            case "uuid" -> Optional.of(randomUuid());
            case "timestamp" -> Optional.of(String.valueOf(clock.millis()));
            case "nonce" -> Optional.of(randomNonce());
            case "randomAlnum" -> Optional.of(randomAlnum());
            default -> Optional.empty();
        };
    }

    private String randomUuid() {
        byte[] bytes = new byte[16];
        secureRandom.nextBytes(bytes);
        bytes[6] &= 0x0f;
        bytes[6] |= 0x40;
        bytes[8] &= 0x3f;
        bytes[8] |= 0x80;

        long mostSignificantBits = 0L;
        long leastSignificantBits = 0L;
        for (int i = 0; i < 8; i++) {
            mostSignificantBits = (mostSignificantBits << 8) | (bytes[i] & 0xffL);
        }
        for (int i = 8; i < 16; i++) {
            leastSignificantBits = (leastSignificantBits << 8) | (bytes[i] & 0xffL);
        }
        return new UUID(mostSignificantBits, leastSignificantBits).toString();
    }

    private String randomNonce() {
        byte[] bytes = new byte[NONCE_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String randomAlnum() {
        StringBuilder value = new StringBuilder(RANDOM_ALNUM_LENGTH);
        for (int i = 0; i < RANDOM_ALNUM_LENGTH; i++) {
            value.append(ALPHANUM[secureRandom.nextInt(ALPHANUM.length)]);
        }
        return value.toString();
    }
}
