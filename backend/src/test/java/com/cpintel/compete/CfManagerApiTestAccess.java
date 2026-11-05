package com.cpintel.compete;

import com.cpintel.integration.codeforces.CfModels;

/** Opens CfManagerApi's package-private matcher to tests in other packages. */
public final class CfManagerApiTestAccess {
    private CfManagerApiTestAccess() {}

    public static boolean byHandle(CfModels.Submission s, String handle) {
        return CfManagerApi.byHandle(s, handle);
    }
}
