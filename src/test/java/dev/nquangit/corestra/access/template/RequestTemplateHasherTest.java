package dev.nquangit.corestra.access.template;

import dev.nquangit.corestra.flow.StoredRequestTemplate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class RequestTemplateHasherTest {
    @Test
    void sameRequestProducesSameHash() {
        RequestTemplateHasher hasher = new RequestTemplateHasher();

        assertEquals(hasher.hash(sample("/accounts", "X-Test: one", "")),
                hasher.hash(sample("/accounts", "X-Test: one", "")));
    }

    @Test
    void differentPathHeaderOrBodyProducesDifferentHash() {
        RequestTemplateHasher hasher = new RequestTemplateHasher();
        String original = hasher.hash(sample("/accounts", "X-Test: one", "body-one"));

        assertNotEquals(original, hasher.hash(sample("/cards", "X-Test: one", "body-one")));
        assertNotEquals(original, hasher.hash(sample("/accounts", "X-Test: two", "body-one")));
        assertNotEquals(original, hasher.hash(sample("/accounts", "X-Test: one", "body-two")));
    }

    private static StoredRequestTemplate sample(String path, String header, String body) {
        return new StoredRequestTemplate("POST", "https", "example.test", 443, path, List.of(header), body);
    }
}
