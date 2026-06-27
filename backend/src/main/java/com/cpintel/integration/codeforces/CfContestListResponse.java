package com.cpintel.integration.codeforces;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class CfContestListResponse {
    private String status;
    private List<Object> result;
}
