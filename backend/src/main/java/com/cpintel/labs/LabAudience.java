package com.cpintel.labs;

import com.cpintel.entity.ClassroomMember;
import com.cpintel.entity.GroupMember;
import com.cpintel.entity.LabAssignment;
import com.cpintel.entity.User;
import com.cpintel.repository.jpa.ClassroomMemberRepository;
import com.cpintel.repository.jpa.GroupMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Who a lab is set for: its classroom's students, or only those in its groups. */
@Component
@RequiredArgsConstructor
public class LabAudience {

    private final ClassroomMemberRepository members;
    private final GroupMemberRepository groupMembers;

    public List<User> students(LabAssignment lab) {
        Map<Long, User> byId = new LinkedHashMap<>();
        for (ClassroomMember m : members.findByClassroom(lab.getClassroomId())) {
            byId.put(m.getUser().getUserId(), m.getUser());
        }
        if (lab.getGroupIds() == null || lab.getGroupIds().isEmpty()) {
            return sorted(byId);
        }
        Map<Long, User> inGroups = new LinkedHashMap<>();
        for (Long groupId : lab.getGroupIds()) {
            for (GroupMember gm : groupMembers.findByGroup(groupId)) {
                Long id = gm.getUser().getUserId();
                // A team only holds students of its classroom, but check rather than assume.
                if (byId.containsKey(id)) inGroups.put(id, byId.get(id));
            }
        }
        return sorted(inGroups);
    }

    public boolean includes(LabAssignment lab, Long userId) {
        if (lab.getGroupIds() == null || lab.getGroupIds().isEmpty()) {
            return members.existsByClassroomIdAndUserUserId(lab.getClassroomId(), userId);
        }
        return lab.getGroupIds().stream()
            .anyMatch(g -> groupMembers.existsByGroupGroupIdAndUserUserId(g, userId));
    }

    private static List<User> sorted(Map<Long, User> users) {
        return users.values().stream()
            .sorted(Comparator.comparing(User::getUsername, String.CASE_INSENSITIVE_ORDER))
            .toList();
    }
}
