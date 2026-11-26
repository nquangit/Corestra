package dev.nquangit.corestra.http.extract;

import dev.nquangit.corestra.flow.rule.ExtractRule;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * An editor selection translated into regex-suggestion coordinates.
 *
 * <p>{@code start}/{@code end} are offsets into
 * {@code snapshot.regexInput(source)}, i.e. exactly the text a regex extract rule searches at
 * runtime. A selection inside the start line or headers maps to {@code HEADERS}, one inside the
 * body maps to {@code BODY}, and one crossing the header/body boundary maps to
 * {@code FULL_MESSAGE}. Works for both request and response editors.
 *
 * @param side     the message side the selection came from
 * @param snapshot the normalised message snapshot
 * @param source   the regex section containing the selection
 * @param start    inclusive start offset in the section text
 * @param end      exclusive end offset in the section text
 */
public record RegexSelection(
        ExtractRule.SourceSide side,
        HttpMessageSnapshot snapshot,
        ExtractRule.RegexSource source,
        int start,
        int end) {

    /** Validates components. */
    public RegexSelection {
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(source, "source");
    }

    /**
     * Maps a selection in a raw HTTP message to regex-section coordinates.
     *
     * @param side  request or response
     * @param head  the raw message head: start line, header lines and the blank separator,
     *              with either CRLF or LF line endings
     * @param body  the decoded body as runtime snapshots see it
     * @param start inclusive selection start, as a character offset into {@code head + body}
     * @param end   exclusive selection end, as a character offset into {@code head + body}
     * @return the mapped selection, or empty when the selection is empty, out of range, or lies
     *         entirely within the blank header/body separator
     */
    public static Optional<RegexSelection> fromRawMessage(
            ExtractRule.SourceSide side, String head, String body, int start, int end) {
        Objects.requireNonNull(side, "side");
        String rawHead = Objects.requireNonNullElse(head, "");
        String rawBody = Objects.requireNonNullElse(body, "");
        int bodyStart = rawHead.length();
        if (start < 0 || end <= start || end > bodyStart + rawBody.length()) {
            return Optional.empty();
        }

        // Split the head into lines, remembering where each line starts in the raw text.
        List<String> lines = new ArrayList<>();
        List<Integer> lineStarts = new ArrayList<>();
        int position = 0;
        while (position < rawHead.length()) {
            int newline = rawHead.indexOf('\n', position);
            int lineEnd = newline < 0 ? rawHead.length() : newline;
            int contentEnd = lineEnd > position && rawHead.charAt(lineEnd - 1) == '\r' ? lineEnd - 1 : lineEnd;
            String line = rawHead.substring(position, contentEnd);
            if (line.isEmpty()) {
                break; // blank separator — headers end here
            }
            lines.add(line);
            lineStarts.add(position);
            position = newline < 0 ? rawHead.length() : newline + 1;
        }
        if (lines.isEmpty()) {
            return Optional.empty();
        }
        HttpMessageSnapshot snapshot = new HttpMessageSnapshot(
                lines.get(0), List.copyOf(lines.subList(1, lines.size())), rawBody);
        int headTextLength = snapshot.regexInput(ExtractRule.RegexSource.HEADERS).length();

        int mappedStart = mapHeadOffset(start, lines, lineStarts);
        boolean startInHead = mappedStart >= 0;
        boolean endInHead = end <= bodyStart && mapHeadOffset(end - 1, lines, lineStarts) >= 0;

        if (startInHead && endInHead) {
            int mappedEnd = mapHeadOffset(end - 1, lines, lineStarts) + 1;
            return Optional.of(new RegexSelection(side, snapshot, ExtractRule.RegexSource.HEADERS, mappedStart, mappedEnd));
        }
        if (start >= bodyStart) {
            return Optional.of(new RegexSelection(side, snapshot, ExtractRule.RegexSource.BODY,
                    start - bodyStart, end - bodyStart));
        }
        if (startInHead && end > bodyStart) {
            int fullBody = headTextLength + 4;
            return Optional.of(new RegexSelection(side, snapshot, ExtractRule.RegexSource.FULL_MESSAGE,
                    mappedStart, fullBody + (end - bodyStart)));
        }
        return Optional.empty();
    }

    /**
     * Maps a raw head offset to the normalised CRLF head text, or {@code -1} when it falls on a
     * line terminator or in the separator.
     */
    private static int mapHeadOffset(int offset, List<String> lines, List<Integer> lineStarts) {
        int normalisedLineStart = 0;
        for (int index = 0; index < lines.size(); index++) {
            int rawLineStart = lineStarts.get(index);
            int column = offset - rawLineStart;
            if (column >= 0 && column < lines.get(index).length()) {
                return normalisedLineStart + column;
            }
            normalisedLineStart += lines.get(index).length() + 2;
        }
        return -1;
    }
}
