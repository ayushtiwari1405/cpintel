import com.cpintel.roadmap.RoadmapTaxonomy;
import com.cpintel.roadmap.SkillAttribution;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Which roadmap nodes each problem belongs to, by the backend's own rules, so the evaluation
 * scores exactly what the product rates. Run by evaluate.py:
 *
 *   java -cp backend/target/classes scripts/analytics-eval/AttributionExport.java in.tsv out.tsv
 *
 * In: contestId, index, rating, tags joined by '|'. Out: contestId, index, node ids joined by
 * '|', and each node id's roll-up topic, joined the same way.
 */
public class AttributionExport {
    public static void main(String[] args) throws Exception {
        List<String> out = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of(args[0]))) {
            String[] f = line.split("\t", -1);
            Set<String> tags = new HashSet<>(List.of(f[3].split("\\|")));
            Set<String> nodes = SkillAttribution.nodesFor(Integer.parseInt(f[2]), tags);
            List<String> topics = new ArrayList<>();
            for (String n : nodes) topics.add(RoadmapTaxonomy.byId(n).rollupTopic());
            out.add(f[0] + "\t" + f[1] + "\t" + String.join("|", nodes) + "\t"
                + String.join("|", topics));
        }
        Files.write(Path.of(args[1]), out);
    }
}
