package dev.nquangit.corestra.access.matrix;

import dev.nquangit.corestra.access.auth.AuthProfile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Classifies auth profiles as privileged or anonymous from their id, name and role.
 *
 * <p>Matching is done on whole words, never substrings, so {@code "Non-admin clerk"},
 * {@code "nonadmin"}, {@code "badminton"} or {@code "groot"} are not treated as administrators.
 * Words are split on non-alphanumeric characters and camelCase boundaries.</p>
 */
public final class RoleClassifier {
    private static final Set<String> ADMIN_WORDS = Set.of(
            "admin", "administrator", "admins", "administrators", "superuser", "superusers", "superadmin",
            "sysadmin", "root");
    private static final Set<String> ANONYMOUS_WORDS = Set.of(
            "anonymous", "anon", "unauthenticated", "unauth", "guest", "guests");
    private static final Set<String> NEGATIONS = Set.of("non", "not", "no");

    /**
     * Returns whether the profile looks like a privileged administrator.
     *
     * @param profile auth profile
     * @return true for an administrator-like profile
     */
    public boolean isAdmin(AuthProfile profile) {
        return profile != null && containsWord(searchableText(profile), ADMIN_WORDS);
    }

    /**
     * Returns whether the profile looks like an unauthenticated actor.
     *
     * @param profile auth profile
     * @return true for an anonymous-like profile
     */
    public boolean isAnonymous(AuthProfile profile) {
        return profile != null && containsWord(searchableText(profile), ANONYMOUS_WORDS);
    }

    /**
     * Returns whether free text contains one of the given words, ignoring negated occurrences.
     *
     * @param text text to inspect
     * @param words lower-case words to look for
     * @return true when a non-negated word is present
     */
    static boolean containsWord(String text, Set<String> words) {
        List<String> tokens = tokens(text);
        for (int index = 0; index < tokens.size(); index++) {
            String token = tokens.get(index);
            if (!words.contains(token) && !compoundMatch(token, words)) {
                continue;
            }
            boolean negated = index > 0 && NEGATIONS.contains(tokens.get(index - 1));
            if (!negated) {
                return true;
            }
        }
        return false;
    }

    /**
     * Matches compound words that end with a privileged/anonymous word, such as {@code orgadmin} or
     * {@code tenantadmins}, but not negated compounds such as {@code nonadmin} or {@code notadmin}.
     */
    private static boolean compoundMatch(String token, Set<String> words) {
        for (String word : words) {
            if (word.length() < 5 || !token.endsWith(word) || token.length() == word.length()) {
                continue;
            }
            String prefix = token.substring(0, token.length() - word.length());
            if (!prefix.startsWith("non") && !prefix.startsWith("not") && !prefix.equals("no")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Splits text into lower-case words on non-alphanumeric characters, camelCase and acronym
     * boundaries ({@code AWSAdmin} → aws, admin) and letter/digit boundaries ({@code admin1}).
     *
     * @param text text to split
     * @return lower-case words
     */
    static List<String> tokens(String text) {
        String value = Objects.requireNonNullElse(text, "");
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (!Character.isLetterOrDigit(ch)) {
                flush(current, tokens);
                continue;
            }
            if (!current.isEmpty()) {
                char previous = value.charAt(index - 1);
                char next = index + 1 < value.length() ? value.charAt(index + 1) : 0;
                boolean camel = Character.isUpperCase(ch) && Character.isLowerCase(previous);
                boolean acronymEnd = Character.isUpperCase(ch) && Character.isUpperCase(previous)
                        && Character.isLowerCase(next);
                boolean letterDigit = Character.isDigit(ch) != Character.isDigit(previous);
                if (camel || acronymEnd || letterDigit) {
                    flush(current, tokens);
                }
            }
            current.append(ch);
        }
        flush(current, tokens);
        return tokens;
    }

    private static void flush(StringBuilder current, List<String> tokens) {
        if (!current.isEmpty()) {
            tokens.add(current.toString().toLowerCase(Locale.ROOT));
            current.setLength(0);
        }
    }

    private static String searchableText(AuthProfile profile) {
        return profile.id() + " " + profile.name() + " " + profile.role();
    }
}
