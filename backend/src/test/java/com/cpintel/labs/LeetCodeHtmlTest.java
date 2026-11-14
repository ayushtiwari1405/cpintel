package com.cpintel.labs;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LeetCodeHtmlTest {

    @Test
    void keepsTheStatementAndDropsAnythingThatRuns() {
        String clean = LeetCodeHtml.clean("""
            <p>Given <code>nums</code>, return x<sup>2</sup>.</p>
            <pre><strong>Input:</strong> nums = [1]</pre>
            <img src="https://assets.leetcode.com/a.png" onerror="alert(1)">
            <img src="http://evil.example/a.png">
            <script>alert(1)</script><a href="javascript:alert(1)">x</a>
            <p style="color:red" onclick="alert(1)">y</p>
            """);
        assertThat(clean).contains("<code>nums</code>", "<sup>2</sup>", "<pre><strong>Input:</strong>",
            "src=\"https://assets.leetcode.com/a.png\"");
        assertThat(clean).doesNotContain("onerror", "script", "javascript:", "onclick", "style=",
            "http://evil.example");
    }

    @Test
    void nothingStaysNothing() {
        assertThat(LeetCodeHtml.clean(null)).isNull();
        assertThat(LeetCodeHtml.clean("  ")).isNull();
    }
}
