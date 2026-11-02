package com.cpintel.questions;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A complete example package to start from: every file the import reads, filled in for a small
 * problem, with a README saying what each one does. It imports into the bank without a single
 * warning (PackageTemplateTest holds it to that), so it doubles as the format's reference.
 */
final class PackageTemplate {

    static final String FILE_NAME = "question-package-template.zip";

    private PackageTemplate() {}

    static byte[] zip() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("README.md", README);
        files.put("problem.yaml", PROBLEM_YAML);
        files.put("domjudge-problem.ini", "# Time limit in seconds, per test. Decimals are fine: 1.5\ntimelimit = 1\n");
        files.put("problem_statement/problem.md", STATEMENT);
        files.put("data/sample/1.in", "3\n1 2 3\n");
        files.put("data/sample/1.ans", "6\n");
        files.put("data/sample/2.in", "1\n-5\n");
        files.put("data/sample/2.ans", "-5\n");
        files.put("data/secret/1.in", "1\n0\n");
        files.put("data/secret/1.ans", "0\n");
        files.put("data/secret/2.in", "5\n1000000000 1000000000 1000000000 1000000000 1000000000\n");
        files.put("data/secret/2.ans", "5000000000\n");
        files.put("data/secret/3.in", "4\n-1 -2 -3 -4\n");
        files.put("data/secret/3.ans", "-10\n");
        files.put("submissions/accepted/solution.cpp", SOLUTION);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> e : files.entrySet()) {
                zip.putNextEntry(new ZipEntry("sum-of-numbers/" + e.getKey()));
                zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not build the template", e);
        }
        return out.toByteArray();
    }

    private static final String PROBLEM_YAML = """
        # The question's title, as candidates see it.
        name: Sum of numbers

        limits:
          # Memory limit in MB (16 to 2048).
          memory: 256

        # How a candidate's output is compared with the answer in each .ans file. Pick one:
        #   (leave both lines out)                 ignore upper and lower case
        #   validator_flags: case_sensitive        exact words and numbers, case included
        #   validator_flags: float_tolerance 1e-6  numbers may differ by 1e-6, absolute or relative
        # Spaces and line breaks never matter. Custom checker programs are not supported yet.
        validation: default
        validator_flags: case_sensitive
        """;

    private static final String STATEMENT = """
        You are given $n$ integers $a_1, a_2, \\ldots, a_n$. Print their sum.

        ## Input

        The first line contains an integer $n$ ($1 \\le n \\le 2 \\cdot 10^5$).

        The second line contains $n$ integers $a_1, a_2, \\ldots, a_n$ ($|a_i| \\le 10^9$).

        ## Output

        Print one integer: $\\sum_{i=1}^{n} a_i$.

        ## Notes

        The sum can be as large as $2 \\cdot 10^{14}$, which does not fit in a 32-bit integer.

        The examples are added below this text automatically, from the samples.
        """;

    private static final String SOLUTION = """
        #include <bits/stdc++.h>
        using namespace std;

        int main() {
            int n;
            cin >> n;
            long long sum = 0;
            for (int i = 0; i < n; i++) {
                long long x;
                cin >> x;
                sum += x;
            }
            cout << sum << "\\n";
        }
        """;

    private static final String README = """
        # Question package template

        This is a complete example of a question for the CPIntel question bank. Change the
        files, zip the folder, and use **Import package** in the question bank. The same
        layout is what DOMjudge imports, and what **Download package** gives you back.

        ```
        sum-of-numbers/
          problem.yaml                    title, memory limit, how output is compared
          domjudge-problem.ini            time limit
          problem_statement/problem.md    the statement, in Markdown with $maths$
          data/sample/1.in, 1.ans         samples: shown to candidates under the statement
          data/secret/1.in, 1.ans         secret tests: only the judge sees these
          submissions/accepted/solution.cpp   a correct solution
        ```

        ## The statement

        Write it in `problem_statement/problem.md`. Use `$...$` for maths in a line and
        `$$...$$` for a formula on its own line, `## Input` style headings for sections, and
        Markdown tables if you need them. Do not write the examples into the text: the samples
        are added below it automatically.

        Already have a typeset statement? Put it at `problem_statement/problem.pdf` instead
        (1 to 50 pages, no password, no scripts). A LaTeX `.tex` statement cannot be read;
        export it to PDF first.

        ## Tests

        - Each test is an input file `name.in` and its answer `name.ans`, with the same name.
        - They run in the natural order of their names: `2` before `10`.
        - Secret tests may sit in subfolders, such as `data/secret/small/` and
          `data/secret/large/`; folders are taken in order too.
        - A test may be left without its `.ans` file; the question then cannot be used until
          the answer is added, or generated from the solution.
        - Limits: 200 tests and 64 MB of tests per question, 256 KB per sample, 72 MB for the
          whole zip.

        ## The solution

        Put one correct solution in `submissions/accepted/`, as `.cpp`, `.c` or `.py`. The
        question bank checks it against every test before the question can go into an exam.
        If there are several, the first by name is used.

        ## Zipping

        Zip the folder itself or its contents; either works. Files the bank does not use
        (like this README) are ignored.
        """;
}
