package com.cpintel.integration.codeforces;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * Typed view of {@code contest.list}.
 *
 * This is the only public endpoint that describes a contest without dragging the whole
 * ranklist along — {@code contest.standings} refuses every extra parameter for non-gym
 * contests and otherwise returns every row in the contest.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class CfContestMetaResponse {
    private String status;
    private List<CfStandingsResponse.Contest> result;
}
