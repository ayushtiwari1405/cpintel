package com.cpintel.security;

import java.util.Set;

/**
 * The three roles, in one place.
 *
 * They are stored as plain strings on the user row rather than as an enum so that widening the
 * set is a migration and a constant, not a schema rewrite. The {@code ROLE_} prefixed forms are
 * what Spring Security matches on; the bare forms are what the database and the API speak.
 *
 * <p>The tiers:
 * <ul>
 *   <li>{@code USER} — the ordinary product. No console.</li>
 *   <li>{@code ADMIN} — runs the console: accounts, groups, audit trail, file policy.</li>
 *   <li>{@code SUPER_ADMIN} — everything an admin can do, plus the two things that change who
 *       else has access: assigning roles, and creating accounts.</li>
 * </ul>
 *
 * Keeping role assignment out of {@code ADMIN} is the point of the split. An admin who can
 * promote other admins can promote themselves past any limit placed on them, so the tier that
 * hands out privilege has to sit above the tier that uses it.
 */
public final class Roles {

    private Roles() {}

    public static final String USER        = "USER";
    public static final String ADMIN       = "ADMIN";
    public static final String SUPER_ADMIN = "SUPER_ADMIN";

    /** Roles a super admin may assign. Deliberately excludes SUPER_ADMIN — see below. */
    public static final Set<String> ASSIGNABLE = Set.of(USER, ADMIN);

    /** Every role the database will accept, matching the chk_role constraint in V6. */
    public static final Set<String> ALL = Set.of(USER, ADMIN, SUPER_ADMIN);

    /** True for the roles that may open the console at all. */
    public static boolean isAdminLevel(String role) {
        return ADMIN.equals(role) || SUPER_ADMIN.equals(role);
    }

    /**
     * Spring Security expression naming both console roles.
     *
     * Written out rather than relying on the role hierarchy alone so that the filter chain
     * states its own rule, and a change to the hierarchy cannot quietly open or close the
     * admin surface as a side effect.
     */
    public static final String HAS_CONSOLE = "hasAnyRole('ADMIN', 'SUPER_ADMIN')";
    public static final String HAS_SUPER   = "hasRole('SUPER_ADMIN')";
}
