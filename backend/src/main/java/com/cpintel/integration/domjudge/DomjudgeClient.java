package com.cpintel.integration.domjudge;

import com.cpintel.exception.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Reads contests, problems and submissions from a DOMjudge instance, and submits into them.
 *
 * DOMjudge is self-hosted, so unlike Codeforces there is no public endpoint and no shared rate
 * limit — there is an address and a set of credentials that an operator has to provide, and
 * nothing here works until they do. That is why every call checks configuration first and says
 * plainly what is missing rather than failing with a connection error to an empty host.
 *
 * <p>The absence of a shared quota is what makes the compete arena viable at 200 contestants:
 * where the Codeforces path is throttled to two calls a second for the entire deployment, this
 * talks to a machine the operator owns. Even so, nothing here is called per-user per-poll —
 * {@link DomjudgeContestCache} fans one fetch out to every contestant, because 200 clients
 * polling a judge that is also compiling their code is a self-inflicted wound whether or not
 * a rate limiter would have stopped it.
 *
 * <p><b>Submitting.</b> CPIntel submits <em>on behalf of</em> a team using one admin API
 * account, rather than holding 200 contestants' passwords. That is the only model that scales
 * to a room full of people, and it is why {@link #submit} passes {@code team_id} explicitly:
 * without it DOMjudge would attribute every submission in the contest to the admin account.
 */
@Component
@Slf4j
public class DomjudgeClient {

    /** Statements are PDFs and scoreboards are large; the 256KB default truncates both. */
    private static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;

    private final WebClient.Builder builder;

    @Value("${cpintel.domjudge.base-url:}")
    private String baseUrl;

    @Value("${cpintel.domjudge.username:}")
    private String username;

    @Value("${cpintel.domjudge.password:}")
    private String password;

    public DomjudgeClient(WebClient.Builder builder) {
        this.builder = builder;
    }

    /** True when an operator has actually pointed this at an instance. */
    public boolean isConfigured() {
        return StringUtils.hasText(baseUrl);
    }

    /** The instance root, without a trailing slash — for building web-UI URLs. */
    public String root() {
        return baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
    }

    private WebClient client() {
        return clientFor(root() + "/api/v4");
    }

    /**
     * The API client, for collaborators in this package that build their own paths.
     *
     * {@link DomjudgeSampleClient} has to probe several routes that differ between DOMjudge
     * versions, so it needs the configured client rather than a fixed method per route.
     */
    WebClient apiClient() {
        return client();
    }

    /** The web UI rather than the API — a couple of sample routes live only there. */
    WebClient webClient() {
        return clientFor(root());
    }

    private WebClient clientFor(String base) {
        if (!isConfigured()) {
            throw ApiException.badRequest(
                "No DOMjudge instance is configured. Set cpintel.domjudge.base-url "
                + "(and credentials, if the contest is not public) to use DOMjudge contests.");
        }

        WebClient.Builder configured = builder.clone()
            .baseUrl(base)
            .exchangeStrategies(ExchangeStrategies.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_RESPONSE_BYTES))
                .build());

        // Basic auth is what DOMjudge's API speaks. A public contest needs none, so credentials
        // are attached only when they exist rather than sending an empty header.
        if (StringUtils.hasText(username)) {
            String token = Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes(StandardCharsets.UTF_8));
            configured = configured.defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + token);
        }
        return configured.build();
    }

    // ------------------------------------------------------------- contest reads

    public List<DjModels.Contest> getContests() {
        return client().get()
            .uri("/contests")
            .retrieve()
            .bodyToFlux(DjModels.Contest.class)
            .collectList()
            .block(Duration.ofSeconds(20));
    }

    public DjModels.Contest getContest(String contestId) {
        return client().get()
            .uri("/contests/{cid}", contestId)
            .retrieve()
            .bodyToMono(DjModels.Contest.class)
            .block(Duration.ofSeconds(20));
    }

    /**
     * Where the contest is in its life.
     *
     * Five nullable timestamps rather than a phase string — see {@link DjModels.State}. This is
     * the only endpoint that knows a contest has actually begun, so the arena's clock hangs
     * off it.
     */
    public DjModels.State getState(String contestId) {
        return client().get()
            .uri("/contests/{cid}/state", contestId)
            .retrieve()
            .bodyToMono(DjModels.State.class)
            .block(Duration.ofSeconds(15));
    }

    public List<DjModels.ContestProblem> getProblems(String contestId) {
        return client().get()
            .uri("/contests/{cid}/problems", contestId)
            .retrieve()
            .bodyToFlux(DjModels.ContestProblem.class)
            .collectList()
            .block(Duration.ofSeconds(20));
    }

    public List<DjModels.Language> getLanguages(String contestId) {
        return client().get()
            .uri("/contests/{cid}/languages", contestId)
            .retrieve()
            .bodyToFlux(DjModels.Language.class)
            .collectList()
            .block(Duration.ofSeconds(20));
    }

    public List<DjModels.Team> getTeams(String contestId) {
        return client().get()
            .uri("/contests/{cid}/teams", contestId)
            .retrieve()
            .bodyToFlux(DjModels.Team.class)
            .collectList()
            .block(Duration.ofSeconds(20));
    }

    public List<DjModels.JudgementType> getJudgementTypes(String contestId) {
        return client().get()
            .uri("/contests/{cid}/judgement-types", contestId)
            .retrieve()
            .bodyToFlux(DjModels.JudgementType.class)
            .collectList()
            .block(Duration.ofSeconds(20));
    }

    /**
     * The scoreboard for one contest.
     *
     * `public=false` asks for the judge's own view, which is the one that stays truthful after
     * the scoreboard freezes. An admin comparing group members needs that; the frozen public
     * board would silently stop moving in the last hour of every contest.
     */
    public DjModels.Scoreboard getScoreboard(String contestId) {
        return client().get()
            .uri(uriBuilder -> uriBuilder
                .path("/contests/{cid}/scoreboard")
                .queryParam("public", "false")
                .build(contestId))
            .retrieve()
            .bodyToMono(DjModels.Scoreboard.class)
            .block(Duration.ofSeconds(25));
    }

    // --------------------------------------------------------- submissions

    /** Every submission in the contest. Fanned out to contestants by the cache, never per-user. */
    public List<DjModels.Submission> getSubmissions(String contestId) {
        return client().get()
            .uri("/contests/{cid}/submissions", contestId)
            .retrieve()
            .bodyToFlux(DjModels.Submission.class)
            .collectList()
            .block(Duration.ofSeconds(30));
    }

    /** Every judgement in the contest, including superseded ones — filter on {@code valid}. */
    public List<DjModels.Judgement> getJudgements(String contestId) {
        return client().get()
            .uri("/contests/{cid}/judgements", contestId)
            .retrieve()
            .bodyToFlux(DjModels.Judgement.class)
            .collectList()
            .block(Duration.ofSeconds(30));
    }

    /**
     * Sends one solution into the contest as {@code teamId}.
     *
     * The file name matters more than it looks: DOMjudge records it, shows it to the jury, and
     * some configurations infer the language from the extension when the explicit language is
     * ambiguous. So the caller supplies a real extension rather than a placeholder.
     *
     * @return the new submission's id
     */
    public String submit(String contestId, String teamId, String problemId, String languageId,
                         String fileName, String source) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("problem", problemId);
        body.part("language", languageId);
        body.part("team_id", teamId);
        body.part("code[]", new ByteArrayResource(source.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });

        try {
            Map<?, ?> created = client().post()
                .uri("/contests/{cid}/submissions", contestId)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(body.build()))
                .retrieve()
                .bodyToMono(Map.class)
                .block(Duration.ofSeconds(45));

            Object id = created == null ? null : created.get("id");
            if (id == null) {
                throw ApiException.badRequest(
                    "DOMjudge accepted the submission but returned no id for it.");
            }
            return String.valueOf(id);

        } catch (WebClientResponseException e) {
            throw translate(e, teamId);
        }
    }

    /**
     * Turns DOMjudge's own refusal into something a contestant can act on.
     *
     * The 403 case is the one worth naming precisely: it almost always means the API account
     * is not an admin, so submit-on-behalf is refused — a deployment mistake that would
     * otherwise read to the contestant as "your code was rejected".
     */
    private ApiException translate(WebClientResponseException e, String teamId) {
        String detail = e.getResponseBodyAsString();
        if (detail != null && detail.length() > 300) detail = detail.substring(0, 300);

        return switch (e.getStatusCode().value()) {
            case 401, 403 -> ApiException.badRequest(
                "DOMjudge refused the submission for team " + teamId + ". The API account "
                    + "needs admin rights to submit on a team's behalf. " + detail);
            case 404 -> ApiException.notFound(
                "DOMjudge does not recognise that contest, problem or team. " + detail);
            default -> ApiException.badRequest("DOMjudge rejected the submission: " + detail);
        };
    }

    // ---------------------------------------------------------- statements

    /**
     * The problem statement as DOMjudge serves it — a PDF.
     *
     * Returns null rather than throwing when there is no statement attached, which is a normal
     * state for a problem imported without one; the arena then shows the metadata it does have
     * instead of an error page.
     */
    public byte[] getStatement(String contestId, String problemId) {
        try {
            return client().get()
                .uri("/contests/{cid}/problems/{pid}/statement", contestId, problemId)
                .accept(MediaType.APPLICATION_PDF, MediaType.ALL)
                .retrieve()
                .bodyToMono(byte[].class)
                .block(Duration.ofSeconds(30));
        } catch (WebClientResponseException e) {
            log.debug("No statement for {}/{}: {}", contestId, problemId, e.getStatusCode());
            return null;
        }
    }
}
