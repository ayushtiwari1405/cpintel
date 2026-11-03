package com.cpintel.security;

import com.cpintel.exception.ApiException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What a password chosen by its owner has to be: registering, resetting from a link, or
 * changing it from the profile.
 *
 * <p>At least {@value #MIN_LENGTH} characters, with a lower-case letter, an upper-case letter,
 * a digit and a symbol; not one of the passwords every guessing list starts with; and not
 * built around the account's own username or address. The frontend mirrors these rules in
 * {@code frontend/src/utils/passwordPolicy.ts} so the form explains itself before it is sent,
 * but this class is the one that decides.
 *
 * <p>Passwords an administrator sets for somebody else are not checked here: they are usually
 * generated, and they are a first password the owner is told to replace with one of their own.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 10;
    public static final int MAX_LENGTH = 200;

    /** The shortest piece of a username or address worth refusing inside a password. */
    private static final int MIN_IDENTITY_FRAGMENT = 3;

    /**
     * Base words that turn up first in every guessing list, compared after the digits and
     * symbols people tack on to satisfy composition rules are stripped and the usual
     * substitutions undone — so "Password@123" and "P@ssw0rd!" are both refused as "password".
     */
    private static final Set<String> COMMON = Set.of(
        "password", "qwerty", "qwertyuiop", "asdfgh",
        "asdfghjkl", "zxcvbnm", "letmein", "welcome", "admin", "administrator", "iloveyou",
        "monkey", "dragon", "master", "sunshine", "princess", "football", "baseball", "cricket",
        "shadow", "superman", "batman", "trustno", "abc", "abcdef", "abcdefg", "abcdefgh",
        "qazwsx", "changeme", "default", "secret", "login", "cpintel", "student", "college",
        "india", "test", "testing", "user");

    private PasswordPolicy() {}

    /**
     * Every rule this password breaks, in the order a person would fix them; empty when it is
     * acceptable. {@code username} and {@code email} may be null.
     */
    public static List<String> problems(String password, String username, String email) {
        List<String> out = new ArrayList<>();
        if (password == null || password.isEmpty()) {
            out.add("A password is required.");
            return out;
        }
        if (password.length() > MAX_LENGTH) {
            out.add("A password can be at most " + MAX_LENGTH + " characters.");
            return out;
        }
        if (password.length() < MIN_LENGTH) out.add("at least " + MIN_LENGTH + " characters");
        if (password.chars().noneMatch(Character::isLowerCase)) out.add("a lower-case letter");
        if (password.chars().noneMatch(Character::isUpperCase)) out.add("an upper-case letter");
        if (password.chars().noneMatch(Character::isDigit)) out.add("a digit");
        if (password.chars().allMatch(Character::isLetterOrDigit)) out.add("a symbol, such as ! or #");

        String lower = password.toLowerCase(Locale.ROOT);
        if (COMMON.contains(lower.replaceAll("[^a-z]", "")) || COMMON.contains(unLeet(lower))) {
            out.add("not a commonly used password");
        }
        if (containsIdentity(lower, username, email)) {
            out.add("not containing your username or email address");
        }
        return out;
    }

    /** Throws a 400 naming everything missing, so the person fixes it in one go. */
    public static void require(String password, String username, String email) {
        List<String> problems = problems(password, username, email);
        if (problems.isEmpty()) return;
        String first = problems.get(0);
        if (first.endsWith(".")) throw ApiException.badRequest(first);
        throw ApiException.badRequest("Choose a stronger password: it needs "
            + String.join(", ", problems) + ".");
    }

    /** The word under the decoration: ends trimmed to letters, then 0→o, @→a and so on. */
    private static String unLeet(String lower) {
        String core = lower.replaceAll("^[^\\p{L}@$]+|[^\\p{L}]+$", "");
        return core.replace('0', 'o').replace('@', 'a').replace('$', 's').replace('3', 'e')
            .replace('1', 'i').replace('4', 'a').replace('5', 's').replace('7', 't')
            .replaceAll("[^a-z]", "");
    }

    private static boolean containsIdentity(String lowerPassword, String username, String email) {
        List<String> fragments = new ArrayList<>();
        if (username != null) fragments.add(username);
        if (email != null) {
            int at = email.indexOf('@');
            fragments.add(at > 0 ? email.substring(0, at) : email);
        }
        for (String f : fragments) {
            String frag = f.trim().toLowerCase(Locale.ROOT);
            if (frag.length() >= MIN_IDENTITY_FRAGMENT && lowerPassword.contains(frag)) return true;
        }
        return false;
    }
}
