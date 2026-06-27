package com.cpintel.integration.codeforces;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class CfProblemsetResponse {
    private String status;
    private Result result;

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Data
    public static class Result {
        private List<CfModels.Submission.Problem> problems;
    }
}
