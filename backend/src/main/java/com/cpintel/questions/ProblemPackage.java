package com.cpintel.questions;

import com.cpintel.entity.BankQuestion;
import com.cpintel.evaluation.UsernameOrder;
import com.cpintel.exception.ApiException;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.representer.Representer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * A problem package: the zip DOMjudge imports, in the Kattis/ICPC layout.
 *
 * <pre>
 *   problem.yaml                  name, limits.memory (MB), validation / validator_flags
 *   domjudge-problem.ini          timelimit (seconds)
 *   problem_statement/problem.pdf the statement (problem.md is read too, on import)
 *   data/sample/*.in, *.ans       samples
 *   data/secret/**&#47;*.in, *.ans  secret tests, in subfolders or not
 *   submissions/accepted/*        a correct solution
 * </pre>
 *
 * <p><b>Reading</b> never trusts the archive. It is read as a stream with hard caps on entries
 * and on the bytes actually inflated, so a small zip that unpacks to gigabytes stops at the cap.
 * Entry names only sort files into the parts above; nothing is written to disk. Anything the
 * bank cannot represent is either refused (a custom output validator, since judging would
 * silently change) or reported as a warning (a LaTeX statement, extra solutions).
 *
 * <p><b>Writing</b> produces the same layout, the exact bytes later sent to the judge.
 */
final class ProblemPackage {

    static final int MAX_ENTRIES = 2000;
    static final long MAX_INFLATED_BYTES = 72L * 1024 * 1024;
    private static final int MAX_SOURCE_CHARS = 200_000;

    private static final Pattern TEST = Pattern.compile("data/(sample|secret)/(.+)\\.(in|ans)");
    private static final Pattern INI_LINE = Pattern.compile("^\\s*([A-Za-z_]+)\\s*=\\s*(.*?)\\s*$");
    private static final Pattern FLOAT_FLAG =
        Pattern.compile("float_(?:relative_|absolute_)?tolerance\\s+([0-9.eE+-]+)");

    /** One test as read from, or written to, a package. */
    record Test(String name, boolean sample, byte[] input, byte[] answer) {}

    /** Everything a package says about a question. */
    record Contents(
        String title,
        Integer timeLimitMs,
        Integer memoryLimitMb,
        BankQuestion.Checker checker,
        Double floatTolerance,
        String statementMarkdown,
        byte[] statementPdf,
        String modelLanguage,
        String modelSource,
        List<Test> tests,
        List<String> warnings
    ) {}

    private ProblemPackage() {}

    // ------------------------------------------------------------------ reading

    static Contents read(InputStream zip, String fileName) {
        Map<String, byte[]> files = unzip(zip);
        files = withoutWrapper(files);
        List<String> warnings = new ArrayList<>();

        Map<String, Object> yaml = yaml(files.get("problem.yaml"));
        Map<String, String> ini = ini(files.get("domjudge-problem.ini"));

        if (files.keySet().stream().anyMatch(n -> n.startsWith("output_validators/"))
            || "custom".equalsIgnoreCase(String.valueOf(yaml.get("validation")).split("\\s+")[0])) {
            throw ApiException.badRequest("This package judges output with its own validator program. "
                + "The question bank does not support custom checkers yet, so it was not imported.");
        }

        String title = title(yaml.get("name"));
        if (title == null) title = strip(ini.get("name"));
        if (title == null || title.isBlank()) title = stem(fileName);
        if (title.length() > 200) title = title.substring(0, 200);

        Integer timeLimitMs = timeLimit(ini.get("timelimit"), warnings);
        Integer memoryLimitMb = memory(yaml.get("limits"), warnings);

        BankQuestion.Checker checker = BankQuestion.Checker.IGNORE_CASE;
        Double tolerance = null;
        String flags = yaml.get("validator_flags") == null ? "" : String.valueOf(yaml.get("validator_flags"));
        Matcher f = FLOAT_FLAG.matcher(flags);
        if (f.find()) {
            checker = BankQuestion.Checker.FLOAT;
            try {
                tolerance = Double.valueOf(f.group(1));
            } catch (NumberFormatException e) {
                tolerance = 1e-6;
                warnings.add("Could not read the float tolerance \"" + f.group(1) + "\"; 0.000001 is used.");
            }
            if (!(tolerance > 0 && tolerance < 1)) {
                warnings.add("The float tolerance " + f.group(1) + " is outside (0, 1); 0.000001 is used.");
                tolerance = 1e-6;
            }
        } else if (flags.contains("case_sensitive")) {
            checker = BankQuestion.Checker.EXACT;
        }
        if (flags.contains("space_change_sensitive")) {
            warnings.add("The package asks for exact spacing; the bank compares output ignoring spacing.");
        }

        byte[] pdf = null;
        String markdown = null;
        for (String name : List.of("problem_statement/problem.pdf", "problem_statement/problem.en.pdf",
                                   "problem.pdf", "problem.en.pdf")) {
            if (files.containsKey(name)) {
                pdf = files.get(name);
                break;
            }
        }
        for (String name : List.of("problem_statement/problem.md", "problem_statement/problem.en.md")) {
            if (files.containsKey(name)) {
                markdown = new String(files.get(name), StandardCharsets.UTF_8);
                break;
            }
        }
        if (pdf == null && markdown == null) {
            boolean tex = files.keySet().stream().anyMatch(n -> n.startsWith("problem_statement/") && n.endsWith(".tex"));
            warnings.add(tex
                ? "The statement is LaTeX source, which the bank cannot render. Paste its text into the statement, or upload its PDF."
                : "The package has no statement. Write one, or upload its PDF.");
        }

        String language = null;
        String source = null;
        List<String> solutions = files.keySet().stream()
            .filter(n -> n.startsWith("submissions/accepted/") && languageOf(n) != null)
            .sorted(UsernameOrder.NATURAL).toList();
        if (!solutions.isEmpty()) {
            String chosen = solutions.get(0);
            language = languageOf(chosen);
            source = new String(files.get(chosen), StandardCharsets.UTF_8);
            if (source.length() > MAX_SOURCE_CHARS) {
                warnings.add("The accepted solution " + chosen + " is too long to keep; add a model solution by hand.");
                language = null;
                source = null;
            } else if (solutions.size() > 1) {
                warnings.add("The package has " + solutions.size() + " accepted solutions; "
                    + chosen.substring("submissions/accepted/".length()) + " is kept as the model solution.");
            }
        }

        List<Test> tests = tests(files, warnings);
        if (tests.stream().noneMatch(t -> !t.sample())) {
            warnings.add("The package has no secret tests.");
        }
        return new Contents(title, timeLimitMs, memoryLimitMb, checker, tolerance, markdown, pdf,
            language, source, tests, warnings);
    }

    /** Every entry's bytes, read under the caps; directories skipped. */
    private static Map<String, byte[]> unzip(InputStream in) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        long total = 0;
        int entries = 0;
        byte[] buffer = new byte[64 * 1024];
        try (ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) {
                    throw ApiException.badRequest("The package has more than " + MAX_ENTRIES + " files.");
                }
                String name = entry.getName().replace('\\', '/');
                while (name.startsWith("/") || name.startsWith("./")) name = name.substring(name.indexOf('/') + 1);
                if (entry.isDirectory() || name.isEmpty() || name.startsWith("__MACOSX/")
                    || name.endsWith("/.DS_Store") || name.equals(".DS_Store")) {
                    continue;
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                int n;
                while ((n = zip.read(buffer)) > 0) {
                    total += n;
                    if (total > MAX_INFLATED_BYTES) {
                        throw ApiException.badRequest("The package unpacks to more than "
                            + (MAX_INFLATED_BYTES >> 20) + " MB.");
                    }
                    out.write(buffer, 0, n);
                }
                files.put(name, out.toByteArray());
            }
        } catch (IOException | IllegalArgumentException e) {
            throw ApiException.badRequest("That file is not a zip archive CPIntel can read.");
        }
        if (files.isEmpty()) throw ApiException.badRequest("That file is not a problem package: it is empty.");
        return files;
    }

    /** A package zipped from its parent folder has every file under one directory: drop it. */
    private static Map<String, byte[]> withoutWrapper(Map<String, byte[]> files) {
        if (files.containsKey("problem.yaml") || files.keySet().stream().anyMatch(n -> n.startsWith("data/"))) {
            return files;
        }
        String first = files.keySet().iterator().next();
        int slash = first.indexOf('/');
        if (slash < 0) return files;
        String prefix = first.substring(0, slash + 1);
        if (!files.keySet().stream().allMatch(n -> n.startsWith(prefix))) return files;
        Map<String, byte[]> out = new LinkedHashMap<>();
        files.forEach((k, v) -> out.put(k.substring(prefix.length()), v));
        return out;
    }

    private static List<Test> tests(Map<String, byte[]> files, List<String> warnings) {
        Map<String, byte[]> inputs = new TreeMap<>(UsernameOrder.NATURAL);
        Map<String, byte[]> answers = new TreeMap<>(UsernameOrder.NATURAL);
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            Matcher m = TEST.matcher(e.getKey());
            if (!m.matches()) continue;
            String key = m.group(1) + "/" + m.group(2);
            (m.group(3).equals("in") ? inputs : answers).put(key, e.getValue());
        }
        if (inputs.isEmpty()) {
            throw ApiException.badRequest("The package has no tests: expected data/sample/*.in or data/secret/*.in.");
        }
        int strays = (int) answers.keySet().stream().filter(k -> !inputs.containsKey(k)).count();
        if (strays > 0) warnings.add(strays + " answer file" + (strays == 1 ? " has" : "s have") + " no input and " + (strays == 1 ? "was" : "were") + " skipped.");
        List<Test> out = new ArrayList<>();
        // Samples first, then secret tests, each in natural order of their paths.
        for (String group : List.of("sample/", "secret/")) {
            for (Map.Entry<String, byte[]> e : inputs.entrySet()) {
                if (!e.getKey().startsWith(group)) continue;
                String name = e.getKey().substring(group.length());
                if (name.length() > 120) name = name.substring(name.length() - 120);
                out.add(new Test(name, group.equals("sample/"), e.getValue(), answers.get(e.getKey())));
            }
        }
        return out;
    }

    private static Map<String, Object> yaml(byte[] bytes) {
        if (bytes == null) return Map.of();
        try {
            Object doc = new Yaml(new SafeConstructor(new LoaderOptions()))
                .load(new String(bytes, StandardCharsets.UTF_8));
            if (doc instanceof Map<?, ?> map) {
                Map<String, Object> out = new LinkedHashMap<>();
                map.forEach((k, v) -> out.put(String.valueOf(k), v));
                return out;
            }
            return Map.of();
        } catch (RuntimeException e) {
            throw ApiException.badRequest("problem.yaml in the package could not be read.");
        }
    }

    private static Map<String, String> ini(byte[] bytes) {
        Map<String, String> out = new LinkedHashMap<>();
        if (bytes == null) return out;
        for (String line : new String(bytes, StandardCharsets.UTF_8).split("\\R")) {
            Matcher m = INI_LINE.matcher(line);
            if (m.matches()) out.put(m.group(1).toLowerCase(Locale.ROOT), m.group(2));
        }
        return out;
    }

    /** A name, or a name per language: English if there is one, else the first. */
    private static String title(Object name) {
        if (name instanceof String s) return s.strip();
        if (name instanceof Map<?, ?> byLanguage && !byLanguage.isEmpty()) {
            Object en = byLanguage.get("en");
            return String.valueOf(en != null ? en : byLanguage.values().iterator().next()).strip();
        }
        return null;
    }

    private static Integer timeLimit(String value, List<String> warnings) {
        String v = strip(value);
        if (v == null || v.isEmpty()) return null;
        try {
            long ms = Math.round(Double.parseDouble(v) * 1000);
            if (ms < 100 || ms > 20_000) {
                warnings.add("The time limit " + v + " s is outside 0.1-20 s; 1 second is used.");
                return null;
            }
            return (int) ms;
        } catch (NumberFormatException e) {
            warnings.add("Could not read the time limit \"" + v + "\"; 1 second is used.");
            return null;
        }
    }

    private static Integer memory(Object limits, List<String> warnings) {
        if (!(limits instanceof Map<?, ?> map) || map.get("memory") == null) return null;
        try {
            int mb = Integer.parseInt(String.valueOf(map.get("memory")).strip());
            if (mb < 16 || mb > 2048) {
                warnings.add("The memory limit " + mb + " MB is outside 16-2048 MB; 256 MB is used.");
                return null;
            }
            return mb;
        } catch (NumberFormatException e) {
            warnings.add("Could not read the memory limit; 256 MB is used.");
            return null;
        }
    }

    static String languageOf(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".cpp") || lower.endsWith(".cc") || lower.endsWith(".cxx")) return "cpp";
        if (lower.endsWith(".c")) return "c";
        if (lower.endsWith(".py")) return "python3";
        return null;
    }

    private static String strip(String s) {
        if (s == null) return null;
        String t = s.strip();
        if (t.length() >= 2 && (t.startsWith("'") && t.endsWith("'") || t.startsWith("\"") && t.endsWith("\""))) {
            t = t.substring(1, t.length() - 1);
        }
        return t;
    }

    private static String stem(String fileName) {
        String n = fileName == null ? "" : fileName.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1);
        if (n.toLowerCase(Locale.ROOT).endsWith(".zip")) n = n.substring(0, n.length() - 4);
        return n.isBlank() ? "Imported question" : n;
    }

    // ------------------------------------------------------------------ writing

    /** What a package is made from. The statement is already a PDF. */
    record Source(String title, int timeLimitMs, int memoryLimitMb, BankQuestion.Checker checker,
                  Double floatTolerance, byte[] statementPdf, String modelLanguage, String modelSource,
                  List<Test> tests) {}

    static byte[] write(Source s) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            Map<String, Object> yaml = new LinkedHashMap<>();
            yaml.put("name", s.title());
            yaml.put("limits", Map.of("memory", s.memoryLimitMb()));
            String flags = switch (s.checker()) {
                case EXACT -> "case_sensitive";
                case IGNORE_CASE -> null;
                case FLOAT -> "float_tolerance " + String.format(Locale.ROOT, "%s", s.floatTolerance());
            };
            if (flags != null) {
                yaml.put("validation", "default");
                yaml.put("validator_flags", flags);
            }
            DumperOptions options = new DumperOptions();
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            put(zip, "problem.yaml", new Yaml(new Representer(options), options).dump(yaml)
                .getBytes(StandardCharsets.UTF_8));
            put(zip, "domjudge-problem.ini", ("timelimit = " + seconds(s.timeLimitMs()) + "\n")
                .getBytes(StandardCharsets.UTF_8));
            if (s.statementPdf() != null) put(zip, "problem_statement/problem.pdf", s.statementPdf());

            int width = Math.max(2, String.valueOf(s.tests().size()).length());
            int sample = 0;
            int secret = 0;
            for (Test t : s.tests()) {
                int n = t.sample() ? ++sample : ++secret;
                String base = "data/" + (t.sample() ? "sample/" : "secret/") + String.format(Locale.ROOT, "%0" + width + "d", n);
                put(zip, base + ".in", t.input());
                if (t.answer() != null) put(zip, base + ".ans", t.answer());
            }
            if (s.modelSource() != null && s.modelLanguage() != null) {
                String ext = switch (s.modelLanguage()) {
                    case "c" -> "c";
                    case "python3" -> "py";
                    default -> "cpp";
                };
                put(zip, "submissions/accepted/model." + ext, s.modelSource().getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not write the package", e);
        }
        return bytes.toByteArray();
    }

    static String seconds(int ms) {
        return ms % 1000 == 0 ? String.valueOf(ms / 1000) : String.format(Locale.ROOT, "%.3f", ms / 1000.0)
            .replaceAll("0+$", "");
    }

    private static void put(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }
}
