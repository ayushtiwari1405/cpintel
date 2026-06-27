package com.cpintel.repository.jpa;

import com.cpintel.entity.RoadmapNode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface RoadmapNodeRepository extends JpaRepository<RoadmapNode, Long> {
    List<RoadmapNode> findByUserUserIdOrderByOrderIndex(Long userId);
}
