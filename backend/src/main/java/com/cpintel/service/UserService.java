package com.cpintel.service;

import com.cpintel.dto.UserDto;
import com.cpintel.entity.TopicMastery;
import com.cpintel.entity.User;
import com.cpintel.exception.ApiException;
import com.cpintel.mapper.UserMapper;
import com.cpintel.repository.jpa.ContestSummaryRepository;
import com.cpintel.repository.jpa.TopicMasteryRepository;
import com.cpintel.repository.jpa.UserRepository;
import com.cpintel.analytics.UnifiedRatingService;
import com.cpintel.entity.UnifiedScore;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final TopicMasteryRepository topicMasteryRepository;
    private final ContestSummaryRepository contestSummaryRepository;
    private final UserMapper userMapper;
    private final UnifiedRatingService unifiedRatingService;

    @Cacheable(value = "user_profile", key = "#userId")
    public UserDto.Profile getProfile(Long userId) {
        User user = userRepository.findByIdWithPlatforms(userId)
            .orElseThrow(() -> ApiException.notFound("User not found"));
        return userMapper.toProfile(user);
    }

    @Transactional
    @CacheEvict(value = "user_profile", key = "#userId")
    public UserDto.Profile updateProfile(Long userId, UserDto.UpdateRequest req) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> ApiException.notFound("User not found"));

        if (req.getFullName()    != null) user.setFullName(req.getFullName());
        if (req.getCountry()     != null) user.setCountry(req.getCountry());
        if (req.getInstitution() != null) user.setInstitution(req.getInstitution());
        if (req.getAvatarUrl()   != null) user.setAvatarUrl(req.getAvatarUrl());

        return userMapper.toProfile(userRepository.save(user));
    }

    public UserDto.DashboardData getDashboard(Long userId) {
        User user = userRepository.findByIdWithPlatformsAndScore(userId)
            .orElseThrow(() -> ApiException.notFound("User not found"));

        // TOPIC scope: the dashboard shows the five coarse areas the user is strongest in, not
        // their five best individual skill-tree nodes, which at this size would all be beginner
        // nodes from whichever track they happened to start in.
        var topTopics = topicMasteryRepository
            .findStrongest(userId, TopicMastery.Scope.TOPIC).stream().limit(5)
            .map(userMapper::toTopicMasteryDto)
            .collect(Collectors.toList());

        var recentContests = contestSummaryRepository
            .findRecentContests(userId, 5).stream()
            .map(userMapper::toContestSummaryDto)
            .collect(Collectors.toList());

        // Read path: compute the current score for display without persisting it.
        // The stored row is refreshed by AnalyticsScheduler and on analytics refresh.
        double freshScore = unifiedRatingService.computeBreakdown(userId).unifiedScore();
        UnifiedScore us = user.getUnifiedScore();
        if (us != null) us.setUnifiedScore(freshScore);

        return UserDto.DashboardData.builder()
            .user(userMapper.toProfile(user))
            .unifiedScore(userMapper.toUnifiedScoreDto(user.getUnifiedScore()))
            .topTopics(topTopics)
            .recentContests(recentContests)
            .currentStreak(0)
            .build();
    }
}
