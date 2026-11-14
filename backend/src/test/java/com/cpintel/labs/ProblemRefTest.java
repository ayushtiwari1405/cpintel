package com.cpintel.labs;

import com.cpintel.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProblemRefTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "https://codeforces.com/problemset/problem/1700/A",
        "codeforces.com/contest/1700/problem/A",
        "https://www.codeforces.com/contest/1700/problem/a/",
        "1700A", "cf 1700a", "CF:1700 A",
    })
    void readsEveryCodeforcesForm(String input) {
        ProblemRef ref = ProblemRef.parse(input);
        assertThat(ref.platform()).isEqualTo(ProblemRef.CODEFORCES);
        assertThat(ref.key()).isEqualTo("1700/A");
        assertThat(ref.url()).isEqualTo("https://codeforces.com/problemset/problem/1700/A");
        assertThat(ref.isGym()).isFalse();
    }

    @Test
    void keepsSplitIndexesAndGyms() {
        assertThat(ProblemRef.parse("https://codeforces.com/contest/1810/problem/F2").key()).isEqualTo("1810/F2");
        ProblemRef gym = ProblemRef.parse("https://codeforces.com/gym/102000/problem/B");
        assertThat(gym.key()).isEqualTo("102000/B");
        assertThat(gym.isGym()).isTrue();
        assertThat(gym.url()).isEqualTo("https://codeforces.com/gym/102000/problem/B");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "https://leetcode.com/problems/two-sum/",
        "https://leetcode.com/problems/two-sum/description/",
        "leetcode.com/problems/Two-Sum",
        "lc:two-sum",
    })
    void readsEveryLeetCodeForm(String input) {
        ProblemRef ref = ProblemRef.parse(input);
        assertThat(ref.platform()).isEqualTo(ProblemRef.LEETCODE);
        assertThat(ref.key()).isEqualTo("two-sum");
        assertThat(ref.url()).isEqualTo("https://leetcode.com/problems/two-sum/");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "", "https://atcoder.jp/contests/abc300/tasks/abc300_a", "https://codeforces.com/profile/tourist",
        "https://leetcode.com/contest/weekly-contest-300", "https://evil.com/problems/two-sum",
        "https://codeforces.com.evil.com/contest/1/problem/A",
    })
    void refusesAnythingElse(String input) {
        assertThatThrownBy(() -> ProblemRef.parse(input)).isInstanceOf(ApiException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "https://cses.fi/problemset/task/1068", "https://cses.fi/problemset/task/1068/",
        "cses.fi/problemset/view/1068/", "CSES 1068", "cses:1068",
    })
    void readsEveryCsesForm(String input) {
        ProblemRef ref = ProblemRef.parse(input);
        assertThat(ref.platform()).isEqualTo(ProblemRef.CSES);
        assertThat(ref.key()).isEqualTo("1068");
        assertThat(ref.url()).isEqualTo("https://cses.fi/problemset/task/1068/");
    }

    @Test
    void refusesOtherCsesPages() {
        assertThatThrownBy(() -> ProblemRef.parse("https://cses.fi/problemset/")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> ProblemRef.parse("https://cses.fi.evil.com/problemset/task/1068")).isInstanceOf(ApiException.class);
    }
}
