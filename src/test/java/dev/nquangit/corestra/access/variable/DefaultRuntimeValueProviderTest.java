package dev.nquangit.corestra.access.variable;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultRuntimeValueProviderTest {
    @Test
    void timestampUsesInjectedClock() {
        Clock fixedClock = Clock.fixed(Instant.parse("2026-06-02T10:15:30Z"), ZoneOffset.UTC);
        DefaultRuntimeValueProvider provider = new DefaultRuntimeValueProvider(fixedClock, new DeterministicSecureRandom());

        assertEquals("1780395330000", provider.resolve("timestamp").orElseThrow());
    }

    @Test
    void uuidUsesInjectedSecureRandom() {
        DefaultRuntimeValueProvider provider = new DefaultRuntimeValueProvider(Clock.systemUTC(), new DeterministicSecureRandom());

        assertEquals("00010203-0405-4607-8809-0a0b0c0d0e0f", provider.resolve("uuid").orElseThrow());
    }

    @Test
    void nonceIsUrlSafeBase64WithoutPaddingAndDeterministic() {
        DefaultRuntimeValueProvider provider = new DefaultRuntimeValueProvider(Clock.systemUTC(), new DeterministicSecureRandom());
        byte[] bytes = new byte[16];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) index;
        }

        String nonce = provider.resolve("nonce").orElseThrow();

        assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes), nonce);
        assertFalse(nonce.contains("="));
        assertTrue(nonce.matches("[A-Za-z0-9_-]+"));
    }

    @Test
    void randomAlnumIsExactlySixteenAlphanumericCharacters() {
        DefaultRuntimeValueProvider provider = new DefaultRuntimeValueProvider(Clock.systemUTC(), new DeterministicSecureRandom());

        String value = provider.resolve("randomAlnum").orElseThrow();

        assertEquals(16, value.length());
        assertTrue(value.matches("[A-Za-z0-9]+"));
        assertEquals("ABCDEFGHIJKLMNOP", value);
    }

    @Test
    void unsupportedRuntimeKeyReturnsEmpty() {
        DefaultRuntimeValueProvider provider = new DefaultRuntimeValueProvider(Clock.systemUTC(), new DeterministicSecureRandom());

        assertTrue(provider.resolve("missing").isEmpty());
    }

    private static final class DeterministicSecureRandom extends SecureRandom {
        private int byteCounter;
        private int intCounter;

        @Override
        public void nextBytes(byte[] bytes) {
            for (int index = 0; index < bytes.length; index++) {
                bytes[index] = (byte) byteCounter++;
            }
        }

        @Override
        public int nextInt(int bound) {
            return Math.floorMod(intCounter++, bound);
        }
    }
}
