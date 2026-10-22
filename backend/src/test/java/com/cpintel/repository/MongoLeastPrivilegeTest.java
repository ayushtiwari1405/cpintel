package com.cpintel.repository;

import com.cpintel.entity.mongo.CfStatementDoc;
import com.cpintel.entity.mongo.CfSubmission;
import com.cpintel.entity.mongo.CodeSubmission;
import com.cpintel.entity.mongo.ContestFileRule;
import com.cpintel.entity.mongo.ContestSnapshotDoc;
import com.cpintel.entity.mongo.PersonalFile;
import com.cpintel.config.MongoConfig;
import com.cpintel.events.EventExportStore;
import com.cpintel.repository.mongo.CfStatementRepository;
import com.cpintel.repository.mongo.CfSubmissionRepository;
import com.cpintel.repository.mongo.CodeSubmissionRepository;
import com.cpintel.repository.mongo.ContestFileRuleRepository;
import com.cpintel.repository.mongo.ContestSnapshotRepository;
import com.cpintel.repository.mongo.PersonalFileRepository;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoSecurityException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The backend against MongoDB the way a fresh installation sets it up: the repository's own
 * mongo-init.js creates the root account and the backend's account, and every Mongo repository,
 * the sync's update, GridFS and startup index creation run as the backend's account only.
 * The same account is then shown to be refused anything administrative.
 *
 * <p>Uses a throwaway mongo:7.0 container (the image docker-compose.yml runs). Skipped without
 * Docker outside CI, like {@link PostgresSchemaTest}.
 */
@DataMongoTest
// MongoConfig is the application's own client and template, which @DataMongoTest would otherwise
// replace with Spring Boot's: the indexes below must come from the wiring that ships.
@Import({ EventExportStore.class, MongoConfig.class })
@DisabledIf("noDockerOutsideCi")
class MongoLeastPrivilegeTest {

    private static final String ROOT_PASSWORD = UUID.randomUUID().toString();
    private static final String APP_PASSWORD = UUID.randomUUID().toString();

    // Not @Container: the Spring context is built from it in @DynamicPropertySource, so it is
    // started there and stopped after the class.
    static final GenericContainer<?> MONGO = new GenericContainer<>("mongo:7.0")
        .withEnv("MONGO_INITDB_ROOT_USERNAME", "root")
        .withEnv("MONGO_INITDB_ROOT_PASSWORD", ROOT_PASSWORD)
        .withEnv("MONGO_INITDB_DATABASE", "cpintel")
        .withEnv("MONGO_APP_USER", "cpintel_app")
        .withEnv("MONGO_APP_PASSWORD", APP_PASSWORD)
        .withCopyFileToContainer(MountableFile.forHostPath(Path.of("..", "mongo-init.js")),
            "/docker-entrypoint-initdb.d/init.js")
        .withExposedPorts(27017)
        // The entrypoint starts mongod once to run the init scripts, then again for real.
        .waitingFor(Wait.forLogMessage(".*Waiting for connections.*\\n", 2))
        // Now and then the real start finds 27017 still held by the init-script run and exits
        // with code 48 ("Address already in use"). docker-compose.yml's restart policy covers
        // that in a deployment; here a fresh container is the same remedy.
        .withStartupAttempts(3);

    static boolean noDockerOutsideCi() {
        return System.getenv("CI") == null && !DockerClientFactory.instance().isDockerAvailable();
    }

    private static String uri(String user, String password, String authSource) {
        return "mongodb://" + user + ":" + password + "@" + MONGO.getHost() + ":" + MONGO.getMappedPort(27017)
            + "/cpintel?authSource=" + authSource;
    }

    @DynamicPropertySource
    static void mongo(DynamicPropertyRegistry registry) {
        if (!MONGO.isRunning()) MONGO.start();
        registry.add("spring.data.mongodb.uri", () -> uri("cpintel_app", APP_PASSWORD, "cpintel"));
    }

    private static MongoClient app;

    @BeforeAll
    static void connectAsApp() {
        // Before the Spring context in JUnit's order, so it may be first to need the container.
        if (!MONGO.isRunning()) MONGO.start();
        app = MongoClients.create(uri("cpintel_app", APP_PASSWORD, "cpintel"));
    }

    @AfterAll
    static void stop() {
        if (app != null) app.close();
        MONGO.stop();
    }

    @Autowired private MongoTemplate mongo;
    @Autowired private CfSubmissionRepository cfSubmissions;
    @Autowired private CodeSubmissionRepository codeSubmissions;
    @Autowired private PersonalFileRepository personalFiles;
    @Autowired private ContestFileRuleRepository fileRules;
    @Autowired private ContestSnapshotRepository snapshots;
    @Autowired private CfStatementRepository statements;
    @Autowired private EventExportStore exports;

    @Test
    @DisplayName("the backend signs in as the app account, with readWrite on cpintel and nothing else")
    void signsInAsApp() {
        Document status = mongo.executeCommand(new Document("connectionStatus", 1));
        Document auth = status.get("authInfo", Document.class);
        assertThat(auth.getList("authenticatedUsers", Document.class))
            .extracting(u -> u.getString("user") + "@" + u.getString("db"))
            .containsExactly("cpintel_app@cpintel");
        assertThat(auth.getList("authenticatedUserRoles", Document.class))
            .extracting(r -> r.getString("role") + "@" + r.getString("db"))
            .containsExactly("readWrite@cpintel");
    }

    @Test
    @DisplayName("startup index creation runs as the app account")
    void indexesCreated() {
        assertThat(mongo.indexOps(PersonalFile.class).getIndexInfo())
            .anyMatch(i -> i.getName().equals("idx_files_user_name") && i.isUnique());
        assertThat(mongo.indexOps(CfSubmission.class).getIndexInfo())
            .anyMatch(i -> i.getName().equals("idx_cf_user_verdict"));
        assertThat(mongo.indexOps(ContestFileRule.class).getIndexInfo())
            .anyMatch(i -> i.getName().equals("idx_file_rule_contest"));
    }

    @Test
    @DisplayName("submissions are written, read, updated as the sync does, and deleted")
    void submissions() {
        CfSubmission s = cfSubmissions.save(CfSubmission.builder().userId(7L).cfSubmissionId(1L).contestId(1900)
            .problemIndex("A").problemName("Pair Sums").problemRating(1200).tags(List.of("math"))
            .verdict("WRONG_ANSWER").submittedAt(Instant.now()).build());
        assertThat(cfSubmissions.findByUserId(7L)).hasSize(1);

        var result = mongo.updateFirst(Query.query(Criteria.where("_id").is(s.getId())),
            Update.update("verdict", "OK"), CfSubmission.class);
        assertThat(result.getModifiedCount()).isEqualTo(1);
        assertThat(cfSubmissions.findById(s.getId())).get().extracting(CfSubmission::getVerdict).isEqualTo("OK");

        cfSubmissions.deleteByUserId(7L);
        assertThat(cfSubmissions.findByUserId(7L)).isEmpty();

        CodeSubmission code = codeSubmissions.save(CodeSubmission.builder().userId(7L).platform("CODEFORCES")
            .contestId("1900").problemIndex("A").source("int main(){}").submittedAt(Instant.now()).build());
        assertThat(codeSubmissions.findById(code.getId())).isPresent();
        codeSubmissions.delete(code);
        assertThat(codeSubmissions.findById(code.getId())).isEmpty();
    }

    @Test
    @DisplayName("personal files keep their content and their unique name per user")
    void personalFiles() {
        byte[] content = "#include <bits/stdc++.h>".getBytes(StandardCharsets.UTF_8);
        PersonalFile f = personalFiles.save(PersonalFile.builder().userId(8L).name("template.cpp")
            .contentType("text/x-c++src").sizeBytes(content.length).textual(true).content(content)
            .uploadedAt(Instant.now()).updatedAt(Instant.now()).build());
        assertThat(personalFiles.findById(f.getId())).get().extracting(PersonalFile::getContent).isEqualTo(content);

        assertThatThrownBy(() -> personalFiles.save(PersonalFile.builder().userId(8L).name("template.cpp")
            .content(content).uploadedAt(Instant.now()).build()))
            .isInstanceOf(DuplicateKeyException.class);

        personalFiles.deleteById(f.getId());
        assertThat(personalFiles.findById(f.getId())).isEmpty();
    }

    @Test
    @DisplayName("contest file rules, snapshots and statements are written and read")
    void otherCollections() {
        ContestFileRule rule = fileRules.save(ContestFileRule.builder().platform("DOMJUDGE").contestId("mid")
            .enabled(false).updatedBy(1L).updatedAt(Instant.now()).build());
        assertThat(fileRules.findById(rule.getId())).isPresent();

        ContestSnapshotDoc snap = snapshots.save(ContestSnapshotDoc.builder().userId(9L).platform("CODEFORCES")
            .contestId("1900").snapshotData(Map.of("rank", 12)).capturedAt(Instant.now()).build());
        assertThat(snapshots.findById(snap.getId())).isPresent();

        CfStatementDoc st = statements.save(CfStatementDoc.builder().id("1900A").name("Pair Sums")
            .fetchedAt(Instant.now()).build());
        assertThat(statements.findById(st.getId())).isPresent();
    }

    @Test
    @DisplayName("event exports are stored in, read from and deleted from GridFS")
    void gridFs() throws Exception {
        byte[] zip = new byte[] { 'P', 'K', 3, 4, 1, 2, 3 };
        String id = exports.save("midterm.zip", zip);
        assertThat(exports.open(id)).get().satisfies(r -> assertThat(r.getInputStream().readAllBytes()).isEqualTo(zip));
        exports.delete(id);
        assertThat(exports.open(id)).isEmpty();
    }

    @Test
    @DisplayName("the app account cannot create accounts, read account data, or touch another database")
    void refusedAdministration() {
        var cpintel = app.getDatabase("cpintel");
        assertThatThrownBy(() -> cpintel.runCommand(new Document("createUser", "intruder")
            .append("pwd", "x").append("roles", List.of())))
            .isInstanceOf(MongoCommandException.class).hasMessageContaining("not authorized");
        assertThatThrownBy(() -> app.getDatabase("admin").getCollection("system.users").find().first())
            .isInstanceOf(MongoCommandException.class).hasMessageContaining("not authorized");
        assertThatThrownBy(() -> app.getDatabase("other").getCollection("x").insertOne(new Document("a", 1)))
            .hasMessageContaining("not authorized");
        assertThatThrownBy(() -> cpintel.runCommand(new Document("dropDatabase", 1)))
            .isInstanceOf(MongoCommandException.class).hasMessageContaining("not authorized");
        assertThatThrownBy(() -> app.getDatabase("admin").runCommand(new Document("shutdown", 1)))
            .isInstanceOf(MongoCommandException.class).hasMessageContaining("not authorized");
    }

    @Test
    @DisplayName("the app account authenticates against cpintel, not admin")
    void notAnAdminAccount() {
        try (MongoClient wrongSource = MongoClients.create(uri("cpintel_app", APP_PASSWORD, "admin"))) {
            assertThatThrownBy(() -> wrongSource.getDatabase("cpintel").runCommand(new Document("ping", 1)))
                .isInstanceOf(MongoSecurityException.class);
        }
    }
}
