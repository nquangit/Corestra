package dev.nquangit.corestra.flow.execution;

import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import dev.nquangit.corestra.flow.rule.ExtractRule;
import dev.nquangit.corestra.flow.rule.InjectRule;
import dev.nquangit.corestra.http.extract.ExtractionService;
import dev.nquangit.corestra.http.inject.InjectionService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BinaryResponseFlowTest {
    @Test
    void extractsExactBinaryBodyAsDataUrlAndInjectsItIntoSecondRequestsJson() throws Exception {
        byte[] image = new byte[256];
        for (int i = 0; i < image.length; i++) image[i] = (byte) i;
        ExtractRule extraction = new ExtractRule("extract", "Image body", true, ExtractRule.SourceSide.RESPONSE,
                ExtractRule.ExtractorType.BODY_RAW, "", "imageUrl",
                List.of("base64Encode", "prefix:data:image/png;base64,"));
        InjectRule injection = new InjectRule("inject", "Image", true, InjectRule.Target.REQUEST_JSON_PATH,
                "image", InjectRule.ValueSourceType.VARIABLE, "imageUrl", "", InjectRule.WriteStrategy.OVERWRITE);
        FlowStep download = step("download", "GET", "/image", "", List.of(extraction), List.of());
        FlowStep upload = step("upload", "POST", "/upload", "{\"image\":\"replace-me\"}", List.of(), List.of(injection));
        FlowDefinition flow = new FlowDefinition("flow", "Image flow", "", true,
                List.of(download, upload), null, null, null);
        List<String> sentBodies = new ArrayList<>();
        StepExecutor executor = new StepExecutor(new ExtractionService(), new InjectionService()) {
            @Override
            HttpRequest buildMontoyaRequest(StoredRequestTemplate template, String line, List<String> headers, String body) {
                sentBodies.add(body);
                return TestFlowEngineFactory.storedRequest(template, line, headers, body);
            }
        };
        FlowEngine engine = new FlowEngine(executor, request -> response(image));

        ExecutionResult result = engine.execute(flow, ExecutionContext.start("run", flow.id(), Map.of(), 0));

        assertTrue(result.success(), result.failureMessage());
        assertEquals(2, sentBodies.size());
        String dataUrl = new ObjectMapper().readTree(sentBodies.get(1)).get("image").asText();
        assertEquals("data:image/png;base64," + Base64.getEncoder().encodeToString(image), dataUrl);
        assertArrayEquals(image, Base64.getDecoder().decode(dataUrl.substring(dataUrl.indexOf(',') + 1)));
        assertEquals(dataUrl, result.finalContext().orElseThrow().variables().get("imageUrl"));
        assertFalse(dataUrl.contains("\n"));
    }

    private static FlowStep step(String id, String method, String path, String body,
                                 List<ExtractRule> extracts, List<InjectRule> injects) {
        return new FlowStep(id, id, true, "", new StoredRequestTemplate(method, "https", "example.test", 443,
                path, List.of("Content-Type: application/json"), body), extracts, injects, List.of(),
                FlowStep.StepErrorStrategy.FAIL_FLOW);
    }

    private static HttpResponse response(byte[] image) {
        ByteArray bytes = proxy(ByteArray.class, (method, arguments) -> switch (method) {
            case "getBytes" -> Arrays.copyOf(image, image.length);
            case "length" -> image.length;
            default -> null;
        });
        return proxy(HttpResponse.class, (method, arguments) -> switch (method) {
            case "statusCode" -> (short) 200;
            case "httpVersion" -> "HTTP/1.1";
            case "reasonPhrase" -> "OK";
            case "headers" -> List.of();
            case "body" -> bytes;
            // Deliberately lossy: binary extraction must never round-trip through this text.
            case "bodyToString" -> "\uFFFD lossy image text";
            case "toString" -> "HTTP/1.1 200 OK\r\n\r\n\uFFFD lossy image text";
            default -> null;
        });
    }

    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (object, method, arguments) -> invocation.invoke(method.getName(), arguments)));
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(String method, Object[] arguments);
    }
}
