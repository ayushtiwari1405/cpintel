package com.cpintel.classrooms;

import com.cpintel.entity.GroupContest;
import com.cpintel.exception.ApiException;
import com.cpintel.integration.domjudge.JudgeContestRef;
import com.cpintel.repository.jpa.ContestGroupRepository;
import com.cpintel.repository.jpa.GroupContestRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;

/**
 * Keeps each admin inside the classrooms they run, on every admin route at once.
 *
 * <p>Checking in each endpoint was how the gap opened in the first place: the lists were
 * filtered, but a team, event or classroom opened by id was not. This reads the ids in the
 * path instead, so a route added later is covered without anyone remembering to add a check:
 *
 * <ul>
 *   <li>{@code {classroomId}}: that classroom</li>
 *   <li>{@code {groupId}}: the team's classroom</li>
 *   <li>{@code {eventId}}, and {@code {contestId}} under {@code /admin/groups}: the event's</li>
 *   <li>{@code {contestId}} under {@code /admin/contest-files}: the classroom a qualified
 *       DOMjudge id names. A Codeforces contest belongs to no classroom.</li>
 * </ul>
 *
 * <p>Ids carried in a request body (a team to assign, a team to move someone to) are checked
 * by the services, which refuse anything outside the event's or team's own classroom.
 * Superadmins pass everywhere, as {@link ClassroomService#canManage} says.
 */
@Component
@RequiredArgsConstructor
public class ClassroomAccessInterceptor implements HandlerInterceptor {

    private static final String ADMIN = "/api/v1/admin/";

    private final ClassroomService classrooms;
    private final ContestGroupRepository groups;
    private final GroupContestRepository events;

    @Override
    @SuppressWarnings("unchecked")
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        String path = request.getRequestURI();
        if (!path.startsWith(ADMIN)) return true;

        Map<String, String> vars = (Map<String, String>)
            request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (vars == null || vars.isEmpty()) return true;

        Long adminId = currentUser();

        String classroomId = vars.get("classroomId");
        if (classroomId != null) classrooms.requireManaged(adminId, number(classroomId));

        String groupId = vars.get("groupId");
        if (groupId != null) {
            groups.findById(number(groupId))
                .ifPresent(g -> classrooms.requireManaged(adminId, g.getClassroomId()));
        }

        String eventId = vars.get("eventId");
        if (eventId != null) requireEvent(adminId, number(eventId));

        String contestId = vars.get("contestId");
        if (contestId != null) {
            if (path.startsWith(ADMIN + "groups/")) {
                requireEvent(adminId, number(contestId));
            } else if ("DOMJUDGE".equalsIgnoreCase(vars.get("platform"))) {
                classrooms.requireManaged(adminId, JudgeContestRef.parse(contestId).classroomId());
            }
        }
        return true;
    }

    private void requireEvent(Long adminId, Long eventId) {
        // A missing row is left to the endpoint, which answers 404 in its own words.
        events.findById(eventId).map(GroupContest::getClassroomId)
            .ifPresent(id -> classrooms.requireManaged(adminId, id));
    }

    private static Long number(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("'" + raw + "' is not an id.");
        }
    }

    private static Long currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof Long id ? id : null;
    }
}
