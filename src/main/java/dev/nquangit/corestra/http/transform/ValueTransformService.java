package dev.nquangit.corestra.http.transform;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
import java.nio.charset.CharacterCodingException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.PatternSyntaxException;

/**
 * Applies ordered, persisted transforms to text or raw bytes. Conversion to text is explicit.
 *
 * <p>Supported specifications are {@code trim}, {@code lowercase}, {@code uppercase},
 * {@code urlDecode}, {@code urlEncode}, {@code base64Decode}, {@code base64Encode},
 * {@code substring:START[:END]}, {@code prefix:TEXT}, {@code suffix:TEXT},
 * {@code stripPrefix:TEXT}, {@code stripSuffix:TEXT}, and
 * {@code replaceRegex:PATTERN=>REPLACEMENT}, {@code hexEncode}, and
 * {@code decodeText[:CHARSET]}. Binary inputs remain bytes until an encoding or decoding
 * operation produces text; the final output must be text for storage in execution variables.</p>
 */
public final class ValueTransformService {

    /**
     * Applies transforms in declaration order.
     *
     * @param input source value
     * @param transforms transform specifications
     * @return transformed value or an actionable failure
     */
    public ValueTransformResult apply(String input, List<String> transforms) {
        return applyChain(new PipelineValue(Objects.requireNonNull(input, "input"), null), transforms);
    }

    /**
     * Applies transforms to exact bytes, with no implicit charset conversion.
     * {@code substring} slices bytes, {@code base64Encode}/{@code hexEncode} serialize bytes,
     * and {@code decodeText[:CHARSET]} strictly decodes text. Other text operations require
     * a prior conversion. A chain ending with bytes fails rather than inventing a string value.
     *
     * @param input exact source bytes, defensively copied
     * @param transforms ordered transform specifications
     * @return final text value, or a failure describing the incompatible operation
     */
    public ValueTransformResult applyBytes(byte[] input, List<String> transforms) {
        return applyChain(new PipelineValue(null, Objects.requireNonNull(input, "input").clone()), transforms);
    }

    /**
     * Checks type transitions for a raw-byte chain without requiring a captured response.
     * Data-dependent checks such as substring bounds or charset validity run during execution.
     *
     * @param transforms ordered transform specifications
     * @return a type error, or empty when the chain explicitly produces text
     */
    public Optional<String> validateBytesChain(List<String> transforms) {
        List<String> chain = transforms == null ? List.of() : transforms;
        boolean raw = true;
        for (int index = 0; index < chain.size(); index++) {
            String specification = chain.get(index);
            if (specification == null || specification.isBlank()) continue;
            String operation = operation(specification.stripLeading()).toLowerCase(Locale.ROOT);
            if (raw) {
                if (operation.equals("substring")) continue;
                if (operation.equals("base64encode") || operation.equals("hexencode") || operation.equals("decodetext")) {
                    raw = false;
                } else {
                    return Optional.of("Transform #" + (index + 1) + ": " + rawTypeError(operation));
                }
            } else if (operation.equals("decodetext")) {
                return Optional.of("Transform #" + (index + 1) + ": decodeText requires raw bytes; current value is text");
            }
        }
        return raw ? Optional.of(rawResultError()) : Optional.empty();
    }

    private ValueTransformResult applyChain(PipelineValue input, List<String> transforms) {
        PipelineValue value = input;
        List<String> chain = transforms == null ? List.of() : transforms;
        for (int index = 0; index < chain.size(); index++) {
            String specification = chain.get(index);
            if (specification == null || specification.isBlank()) {
                continue;
            }
            try {
                // Leading whitespace is formatting noise, while trailing whitespace may be
                // an intentional prefix/suffix argument (for example "prefix:Bearer ").
                value = applyOne(value, specification.stripLeading());
            } catch (IllegalArgumentException | IndexOutOfBoundsException exception) {
                return ValueTransformResult.failure(value.text() == null ? "" : value.text(),
                        "Transform #" + (index + 1) + " '" + specification + "' failed: "
                                + exception.getMessage());
            }
        }
        if (value.bytes() != null) {
            return ValueTransformResult.failure("", rawResultError());
        }
        return ValueTransformResult.success(value.text());
    }

    private PipelineValue applyOne(PipelineValue value, String specification) {
        String operation = operation(specification).toLowerCase(Locale.ROOT);
        if (value.bytes() == null) {
            if (operation.equals("decodetext")) {
                throw new IllegalArgumentException("decodeText requires raw bytes, but the current value is text");
            }
            return new PipelineValue(applyOne(value.text(), specification), null);
        }
        return switch (operation) {
            case "base64encode" -> new PipelineValue(Base64.getEncoder().encodeToString(value.bytes()), null);
            case "hexencode" -> new PipelineValue(HexFormat.of().formatHex(value.bytes()), null);
            case "decodetext" -> new PipelineValue(decodeText(value.bytes(), argument(specification)), null);
            case "substring" -> {
                Slice slice = slice(value.bytes().length, requiredArgument(operation, argument(specification)));
                yield new PipelineValue(null, Arrays.copyOfRange(value.bytes(), slice.start(), slice.end()));
            }
            default -> throw new IllegalArgumentException(rawTypeError(operation));
        };
    }

    private String rawTypeError(String operation) {
        return operation + " requires text; the current value is raw bytes. "
                + "Use base64Encode, hexEncode, or decodeText[:CHARSET] first";
    }

    private String rawResultError() {
        return "Result is still raw bytes; add base64Encode, hexEncode, "
                + "or decodeText[:CHARSET] before storing a text variable";
    }

    private String decodeText(byte[] bytes, String charsetName) {
        Charset charset = charsetName == null ? StandardCharsets.UTF_8 : Charset.forName(charsetName);
        try {
            return charset.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("raw bytes are not valid " + charset.name() + " text", exception);
        }
    }

    private String applyOne(String value, String specification) {
        String operation = operation(specification).toLowerCase(Locale.ROOT);
        String argument = argument(specification);
        return switch (operation) {
            case "trim" -> value.strip();
            case "lowercase", "lower" -> value.toLowerCase(Locale.ROOT);
            case "uppercase", "upper" -> value.toUpperCase(Locale.ROOT);
            case "urldecode" -> URLDecoder.decode(value, StandardCharsets.UTF_8);
            case "urlencode" -> URLEncoder.encode(value, StandardCharsets.UTF_8);
            case "base64decode" -> new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
            case "base64encode" -> Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
            case "hexencode" -> HexFormat.of().formatHex(value.getBytes(StandardCharsets.UTF_8));
            case "substring" -> substring(value, requiredArgument(operation, argument));
            case "prefix" -> requiredArgument(operation, argument) + value;
            case "suffix" -> value + requiredArgument(operation, argument);
            case "stripprefix" -> value.startsWith(requiredArgument(operation, argument))
                    ? value.substring(argument.length()) : value;
            case "stripsuffix" -> value.endsWith(requiredArgument(operation, argument))
                    ? value.substring(0, value.length() - argument.length()) : value;
            case "replaceregex" -> replaceRegex(value, requiredArgument(operation, argument));
            default -> throw new IllegalArgumentException("unknown transform");
        };
    }

    private String substring(String value, String argument) {
        Slice slice = slice(value.length(), argument);
        return value.substring(slice.start(), slice.end());
    }

    private Slice slice(int length, String argument) {
        String[] bounds = argument.split(":", -1);
        if (bounds.length > 2 || bounds[0].isBlank()) {
            throw new IllegalArgumentException("expected substring:START[:END]");
        }
        int start = parseIndex(bounds[0], "start");
        int end = bounds.length == 2 && !bounds[1].isBlank()
                ? parseIndex(bounds[1], "end") : length;
        if (start < 0 || end < start || end > length) {
            throw new IndexOutOfBoundsException(
                    "range " + start + ":" + end + " is outside value length " + length);
        }
        return new Slice(start, end);
    }

    private String replaceRegex(String value, String argument) {
        int separator = argument.indexOf("=>");
        if (separator < 0) {
            throw new IllegalArgumentException("expected replaceRegex:PATTERN=>REPLACEMENT");
        }
        String pattern = argument.substring(0, separator);
        String replacement = argument.substring(separator + 2);
        try {
            return value.replaceAll(pattern, replacement);
        } catch (PatternSyntaxException exception) {
            throw new IllegalArgumentException("invalid regex: " + exception.getDescription(), exception);
        }
    }

    private int parseIndex(String raw, String name) {
        try {
            return Integer.parseInt(raw.strip());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " index is not an integer: " + raw, exception);
        }
    }

    private String requiredArgument(String operation, String argument) {
        if (argument == null) {
            throw new IllegalArgumentException(operation + " requires an argument");
        }
        return argument;
    }

    private String operation(String specification) {
        int separator = specification.indexOf(':');
        return separator < 0 ? specification : specification.substring(0, separator);
    }

    private String argument(String specification) {
        int separator = specification.indexOf(':');
        return separator < 0 ? null : specification.substring(separator + 1);
    }

    private record PipelineValue(String text, byte[] bytes) { }
    private record Slice(int start, int end) { }
}
