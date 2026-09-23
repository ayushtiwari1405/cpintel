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
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
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
 * <p><b>Two identities, deliberately.</b> Every method here comes in two forms: one that uses
 * the deployment's own configured account, and one that takes a contestant's credentials. They
 * exist for genuinely different jobs and neither replaces the other.
 *
 * <ul>
 *   <li><b>The configured account</b> ({@code cpintel.domjudge.username}) backs the admin-side
 *       group standings board, where one read is fanned out to every member by
 *       {@link com.cpintel.compete.DomjudgeContestCache}. Contest-wide reads — every team,
 *       every submission, the unfrozen scoreboard — generally require it.
 *   <li><b>A contestant's own credentials</b> back the compete arena, because the contest list
 *       a person may enter is a property of <em>their</em> account, and because a submission
 *       has to be attributed to their team. DOMjudge derives the team from the login, which is
 *       why {@link #submitAs} sends no {@code team_id} at all — passing one is an admin-only
 *       operation and a team account is refused it outright.
 * </ul>
 *
 * <p>The cost of the second form is that it cannot be cached across users, so it is used only
 * where the answer genuinely differs per person. Anything contest-wide still goes through the
 * shared cache on the configured account where one exists.
 */
@Component
@Slf4j
public class DomjudgeClient {

    /** Statements are PDFs and scoreboards are large; the 256KB default truncates both. */
    private static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;

    private final WebClient.Builder builder;

    /**
     * A signed-in web session, for the routes the API cannot serve.
     *
     * <p>Lazy because the dependency is a cycle on paper: the session needs a client to log in
     * with, and the client needs a session to read a statement. It is not a cycle in practice —
     * logging in uses only {@link #plainWebClient()}, which touches nothing here but the base
     * URL — and {@code @Lazy} is how that is said to Spring without splitting one cohesive
     * class in two to satisfy the container.
     */
    private final DomjudgeWebSession webSession;

    @Value("${cpintel.domjudge.base-url:}")
    private String baseUrl;

    @Value("${cpintel.domjudge.username:}")
    private String username;

    @Value("${cpintel.domjudge.password:}")
    private String password;

    public DomjudgeClient(WebClient.Builder builder,
                          @org.springframework.context.annotation.Lazy
                          DomjudgeWebSession webSession) {
        this.builder = builder;
        this.webSession = webSession;
    }

    /** True when an operator has actually pointed this at an instance. */
    public boolean isConfigured() {
        return StringUtils.hasText(baseUrl);
    }

    /**
     * True when the deployment has an account of its own, beyond the contestants'.
     *
     * The arena works without one — every read can be made as the contestant — but the shared
     * contest cache and the admin standings board cannot. Callers check this to decide whether
     * a contest-wide read is even worth attempting.
     */
    public boolean hasServiceAccount() {
        return isConfigured() && StringUtils.hasText(username);
    }

    /** The instance root, without a trailing slash — for building web-UI URLs. */
    public String root() {
        return baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
    }

    private WebClient client() {
        return clientFor(root() + "/api/v4", username, password);
    }

    /** The API as one particular contestant, rather than as the deployment. */
    private WebClient client(DomjudgeCredentialStore.Stored as) {
        return as == null ? client()
            : clientFor(root() + "/api/v4", as.username(), as.password());
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

    /** The same, as a contestant — sample routes are readable by a team account too. */
    WebClient apiClient(DomjudgeCredentialStore.Stored as) {
        return client(as);
    }

    /** The web UI rather than the API — a couple of sample routes live only there. */
    WebClient webClient() {
        return clientFor(root(), username, password);
    }

    WebClient webClient(DomjudgeCredentialStore.Stored as) {
        return as == null ? webClient() : clientFor(root(), as.username(), as.password());
    }

    /**
     * The web interface with no credentials attached at all.
     *
     * <p>Distinct from {@link #webClient()}, which sends HTTP Basic. The web firewall does not
     * accept Basic — a valid Basic header is redirected to {@code /login} exactly as an
     * anonymous request is — so sending it there achieves nothing and puts the deployment's
     * password on requests that will never use it. This is what the login exchange and the
     * cookie-bearing statement reads go through.
     *
     * <p>Redirects are not followed, which is load-bearing rather than incidental: a client
     * that followed them would turn "your session lapsed" into a 200 carrying the login page,
     * and the caller would file it as a statement.
     */
    WebClient plainWebClient() {
        if (!isConfigured()) {
            throw ApiException.badRequest(
                "No DOMjudge instance is configured. Set cpintel.domjudge.base-url "
                + "(and credentials, if the contest is not public) to use DOMjudge contests.");
        }
        return builder.clone()
            .baseUrl(root())
            .filter(repairMalformedContentType())
            .exchangeStrategies(ExchangeStrategies.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_RESPONSE_BYTES))
                .build())
            .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(
                reactor.netty.http.client.HttpClient.create().followRedirect(false)))
            .build();
    }

    /**
     * Repairs a {@code Content-Type} header that DOMjudge sends malformed.
     *
     * <p>Its statement route appends a {@code name} parameter and never closes the quote. The
     * real header from a live 8.0.0 instance is:
     *
     * <pre>content-type: application/pdf; name="prob-FIB.pdf</pre>
     *
     * <p>That is not a parseable media type, and WebClient does not merely decline to tell you
     * the type — it throws {@link org.springframework.http.InvalidMediaTypeException} while
     * choosing a decoder, so the whole response is lost. The bytes are a perfectly good PDF
     * sitting behind a header nobody needs: the parameters carry only a filename, which is
     * regenerated downstream from the problem label anyway.
     *
     * <p>So the header is rewritten to its bare media type before the body is read, and
     * <em>only</em> when it does not parse as it stands — a well-formed {@code charset} is left
     * alone, because text statements need it to decode as anything but Latin-1.
     */
    private static ExchangeFilterFunction repairMalformedContentType() {
        return ExchangeFilterFunction.ofResponseProcessor(response -> {
            String raw = response.headers().asHttpHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
            if (raw == null || raw.isBlank()) return reactor.core.publisher.Mono.just(response);

            try {
                MediaType.parseMediaType(raw);
                return reactor.core.publisher.Mono.just(response);
            } catch (Exception malformed) {
                int semicolon = raw.indexOf(';');
                String bare = (semicolon < 0 ? raw : raw.substring(0, semicolon)).trim();
                try {
                    MediaType.parseMediaType(bare);
                } catch (Exception hopeless) {
                    bare = MediaType.APPLICATION_OCTET_STREAM_VALUE;
                }

                // Keep the charset if there is one. It is the one parameter that matters:
                // DOMjudge writes it after the unterminated quote that broke the header —
                // `text/plain; name="prob-X.txt; charset=UTF-8` — so dropping every parameter
                // would take it with them, and a statement with an accent in it would then be
                // decoded as whatever the reader guessed.
                java.util.regex.Matcher charset = java.util.regex.Pattern
                    .compile("charset\\s*=\\s*\"?([A-Za-z0-9_.:+-]+)\"?")
                    .matcher(raw);
                if (charset.find()) {
                    String candidate = bare + ";charset=" + charset.group(1);
                    try {
                        MediaType.parseMediaType(candidate);
                        bare = candidate;
                    } catch (Exception ignored) {
                        // Leave the bare type; an unusable charset is not worth failing over.
                    }
                }
                log.debug("Repaired a malformed Content-Type from DOMjudge: {} -> {}", raw, bare);

                String repaired = bare;
                return reactor.core.publisher.Mono.just(ClientResponse.from(response)
                    .headers(h -> h.set(HttpHeaders.CONTENT_TYPE, repaired))
                    // The body has to be carried across explicitly; from() copies the status
                    // and the headers and nothing else.
                    .body(response.bodyToFlux(org.springframework.core.io.buffer.DataBuffer.class))
                    .build());
            }
        });
    }

    private WebClient clientFor(String base, String user, String secret) {
        if (!isConfigured()) {
            throw ApiException.badRequest(
                "No DOMjudge instance is configured. Set cpintel.domjudge.base-url "
                + "(and credentials, if the contest is not public) to use DOMjudge contests.");
        }

        WebClient.Builder configured = builder.clone()
            .baseUrl(base)
            .filter(repairMalformedContentType())
            .exchangeStrategies(ExchangeStrategies.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_RESPONSE_BYTES))
                .build());

        // Basic auth is what DOMjudge's API speaks. A public contest needs none, so credentials
        // are attached only when they exist rather than sending an empty header.
        if (StringUtils.hasText(user)) {
            String token = Base64.getEncoder().encodeToString(
                (user + ":" + secret).getBytes(StandardCharsets.UTF_8));
            configured = configured.defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + token);
        }
        return configured.build();
    }

    // ------------------------------------------------------------------ identity

    /**
     * Who a set of credentials belongs to, and which team it competes for.
     *
     * The verification step when an admin attaches an account: it proves the password works
     * <em>and</em> reports the team, without which a submission would be accepted by the API
     * and then attributed to nobody. Both failures look identical from the outside until this
     * is called, which is why provisioning calls it rather than trusting a 200 from some other
     * endpoint.
     */
    public DjModels.User whoami(DomjudgeCredentialStore.Stored as) {
        try {
            return client(as).get()
                .uri("/user")
                .retrieve()
                .bodyToMono(DjModels.User.class)
                .block(Duration.ofSeconds(15));
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
                throw ApiException.badRequest(
                    "DOMjudge rejected that username and password.");
            }
            if (e.getStatusCode().value() == 404) {
                // Older builds route this differently. Worth naming, because the natural
                // conclusion from a bare 404 is that the credentials are wrong.
                throw ApiException.badRequest(
                    "This DOMjudge build has no /api/v4/user endpoint, so an account's team "
                        + "cannot be resolved automatically. Check the instance version.");
            }
            throw ApiException.badRequest(
                "Could not reach DOMjudge to verify that account: " + e.getStatusCode());
        }
    }

    // ------------------------------------------------------------- contest reads

    public List<DjModels.Contest> getContests() {
        return getContests(null);
    }

    /**
     * The contests this account may see.
     *
     * Per-account by nature: DOMjudge shows a team the contests it is registered for, and the
     * arena's contest picker is exactly that list. Asking as the deployment's own account
     * would offer people rounds they cannot enter.
     */
    public List<DjModels.Contest> getContests(DomjudgeCredentialStore.Stored as) {
        return client(as).get()
            .uri("/contests")
            .retrieve()
            .bodyToFlux(DjModels.Contest.class)
            .collectList()
            .block(Duration.ofSeconds(20));
    }

    public DjModels.Contest getContest(String contestId) {
        return getContest(null, contestId);
    }

    public DjModels.Contest getContest(DomjudgeCredentialStore.Stored as, String contestId) {
        return client(as).get()
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
        return getState(null, contestId);
    }

    public DjModels.State getState(DomjudgeCredentialStore.Stored as, String contestId) {
        return client(as).get()
            .uri("/contests/{cid}/state", contestId)
            .retrieve()
            .bodyToMono(DjModels.State.class)
            .block(Duration.ofSeconds(15));
    }

    public List<DjModels.ContestProblem> getProblems(String contestId) {
        return getProblems(null, contestId);
    }

    public List<DjModels.ContestProblem> getProblems(DomjudgeCredentialStore.Stored as,
                                                     String contestId) {
        return client(as).get()
            .uri("/contests/{cid}/problems", contestId)
            .retrieve()
            .bodyToFlux(DjModels.ContestProblem.class)
            .collectList()
            .block(Duration.ofSeconds(20));
    }

    public List<DjModels.Language> getLanguages(String contestId) {
        return getLanguages(null, contestId);
    }

    public List<DjModels.Language> getLanguages(DomjudgeCredentialStore.Stored as,
                                                String contestId) {
        return client(as).get()
            .uri("/contests/{cid}/languages", contestId)
            .retrieve()
            .bodyToFlux(DjModels.Language.class)
            .collectList()
            .block(Duration.ofSeconds(20));
    }

    public List<DjModels.Team> getTeams(String contestId) {
        return getTeams(null, contestId);
    }

    public List<DjModels.Team> getTeams(DomjudgeCredentialStore.Stored as, String contestId) {
        return client(as).get()
            .uri("/contests/{cid}/teams", contestId)
            .retrieve()
            .bodyToFlux(DjModels.Team.class)
            .collectList()
            .block(Duration.ofSeconds(20));
    }

    /**
     * Every team the picker can offer, however this build is willing to report them.
     *
     * <p>Wanted instance-wide, because an admin attaches an account before picking a contest —
     * often before the contest exists. But {@code /api/v4/teams} is not universal: the CCS
     * specification scopes teams under a contest, and a DOMjudge 8 instance answers the
     * instance-wide route with a 404 page rather than a 401. Verified against a real 8.0.0
     * deployment, where {@code /teams} 404s and {@code /contests/4/teams} returns the roster.
     *
     * <p>So the union across visible contests is the fallback. A team in two contests appears
     * once, keyed by id. It costs one call per contest, which is acceptable for a screen an
     * admin opens occasionally and is cached by the caller anyway.
     *
     * <p>An empty list stays a normal answer rather than an error: an admin who cannot see the
     * roster can still leave the team to the judge, which is the right default regardless.
     */
    public List<DjModels.Team> getAllTeams(DomjudgeCredentialStore.Stored as) {
        try {
            List<DjModels.Team> all = client(as).get()
                .uri("/teams")
                .retrieve()
                .bodyToFlux(DjModels.Team.class)
                .collectList()
                .block(Duration.ofSeconds(20));
            if (all != null && !all.isEmpty()) return all;
        } catch (WebClientResponseException e) {
            log.debug("Instance-wide /teams not available ({}); falling back to per-contest",
                e.getStatusCode());
        } catch (Exception e) {
            log.debug("Instance-wide /teams failed: {}", e.getMessage());
        }
        return teamsAcrossContests(as);
    }

    private List<DjModels.Team> teamsAcrossContests(DomjudgeCredentialStore.Stored as) {
        List<DjModels.Contest> contests;
        try {
            contests = getContests(as);
        } catch (Exception e) {
            log.debug("Could not list contests to gather teams: {}", e.getMessage());
            return List.of();
        }
        if (contests == null) return List.of();

        java.util.Map<String, DjModels.Team> byId = new java.util.LinkedHashMap<>();
        for (DjModels.Contest contest : contests) {
            if (contest.getId() == null) continue;
            try {
                List<DjModels.Team> teams = getTeams(as, contest.getId());
                if (teams == null) continue;
                for (DjModels.Team team : teams) {
                    if (team.getId() != null) byId.putIfAbsent(team.getId(), team);
                }
            } catch (Exception e) {
                // One unreadable contest must not empty the picker.
                log.debug("Could not read teams for contest {}: {}",
                    contest.getId(), e.getMessage());
            }
        }
        return List.copyOf(byId.values());
    }

    public List<DjModels.JudgementType> getJudgementTypes(String contestId) {
        return getJudgementTypes(null, contestId);
    }

    public List<DjModels.JudgementType> getJudgementTypes(DomjudgeCredentialStore.Stored as,
                                                          String contestId) {
        return client(as).get()
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
        return getScoreboard(null, contestId);
    }

    /**
     * The same board, read as whoever is asking.
     *
     * A team account is refused the jury view on most installations, so a 403 here falls back
     * to the public board rather than failing. That fallback is not cosmetic and the caller is
     * expected to say so on screen: the public board <em>freezes</em>, so during the last hour
     * of a contest it stops moving. Showing a frozen board as though it were live would be a
     * worse outcome than showing nothing, because it looks exactly like nobody is solving
     * anything.
     */
    public DjModels.Scoreboard getScoreboard(DomjudgeCredentialStore.Stored as,
                                             String contestId) {
        try {
            return scoreboard(as, contestId, false);
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().value() != 401 && e.getStatusCode().value() != 403) throw e;
            log.debug("Jury scoreboard refused for contest {}; falling back to public view",
                contestId);
            return scoreboard(as, contestId, true);
        }
    }

    private DjModels.Scoreboard scoreboard(DomjudgeCredentialStore.Stored as, String contestId,
                                           boolean publicView) {
        return client(as).get()
            .uri(uriBuilder -> uriBuilder
                .path("/contests/{cid}/scoreboard")
                .queryParam("public", String.valueOf(publicView))
                .build(contestId))
            .retrieve()
            .bodyToMono(DjModels.Scoreboard.class)
            .block(Duration.ofSeconds(25));
    }

    /**
     * Whether this account can read the unfrozen board.
     *
     * Asked once and cached by the caller, so the arena can label a frozen leaderboard
     * honestly instead of discovering the limitation separately on every poll.
     */
    public boolean canReadJuryScoreboard(DomjudgeCredentialStore.Stored as, String contestId) {
        try {
            scoreboard(as, contestId, false);
            return true;
        } catch (WebClientResponseException e) {
            return e.getStatusCode().value() != 401 && e.getStatusCode().value() != 403;
        } catch (Exception e) {
            return false;
        }
    }

    // --------------------------------------------------------- submissions

    /** Every submission in the contest. Fanned out to contestants by the cache, never per-user. */
    public List<DjModels.Submission> getSubmissions(String contestId) {
        return getSubmissions(null, contestId);
    }

    public List<DjModels.Submission> getSubmissions(DomjudgeCredentialStore.Stored as,
                                                    String contestId) {
        return client(as).get()
            .uri("/contests/{cid}/submissions", contestId)
            .retrieve()
            .bodyToFlux(DjModels.Submission.class)
            .collectList()
            .block(Duration.ofSeconds(30));
    }

    /** Every judgement in the contest, including superseded ones — filter on {@code valid}. */
    public List<DjModels.Judgement> getJudgements(String contestId) {
        return getJudgements(null, contestId);
    }

    public List<DjModels.Judgement> getJudgements(DomjudgeCredentialStore.Stored as,
                                                  String contestId) {
        return client(as).get()
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
        body.part("code[]", sourcePart(fileName, source));

        return post(null, contestId, body, teamId);
    }

    /**
     * Sends one solution into the contest as the contestant themselves.
     *
     * <p>No {@code team_id}, and that omission is the entire point rather than an oversight.
     * DOMjudge attributes a submission to the team behind the authenticated account, and
     * passing an explicit team is an admin-only override that a team account is refused — so
     * the on-behalf form above would fail for exactly the credentials this method exists to
     * serve. Leaving it out lets the judge do what it already knows how to do.
     *
     * @return the new submission's id
     */
    public String submitAs(DomjudgeCredentialStore.Stored as, String contestId, String problemId,
                           String languageId, String fileName, String source) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("problem", problemId);
        body.part("language", languageId);
        body.part("code[]", sourcePart(fileName, source));

        return post(as, contestId, body, as == null ? null : as.teamId());
    }

    private ByteArrayResource sourcePart(String fileName, String source) {
        return new ByteArrayResource(source.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return fileName;
            }
        };
    }

    private String post(DomjudgeCredentialStore.Stored as, String contestId,
                        MultipartBodyBuilder body, String teamId) {
        try {
            Map<?, ?> created = client(as).post()
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
            throw translate(e, teamId, as != null);
        }
    }

    /**
     * Turns DOMjudge's own refusal into something a contestant can act on.
     *
     * The 403 case has two quite different causes depending on which identity sent the
     * request, and conflating them sends whoever is debugging it to the wrong place. As the
     * deployment's account it almost always means that account is not an admin, so
     * submit-on-behalf was refused. As a contestant it usually means the account is not
     * registered for this contest, or the contest is not open to it — a roster problem on the
     * judge, not a permissions one. Either way it must not read to the contestant as "your
     * code was rejected".
     */
    private ApiException translate(WebClientResponseException e, String teamId, boolean asUser) {
        String detail = e.getResponseBodyAsString();
        if (detail != null && detail.length() > 300) detail = detail.substring(0, 300);

        return switch (e.getStatusCode().value()) {
            case 401, 403 -> ApiException.badRequest(asUser
                ? "DOMjudge refused this submission for your account. Usually that means the "
                    + "account is not registered for this contest, or the contest is not open "
                    + "to it. " + detail
                : "DOMjudge refused the submission for team " + teamId + ". The API account "
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
    public Statement getStatement(String contestId, String problemId) {
        return getStatement(null, contestId, problemId);
    }

    /**
     * A problem statement, with the type the judge served it as.
     *
     * <p>The type is carried rather than assumed. DOMjudge serves whatever the problem package
     * contains, and on a real instance that is a mixture — of one seven-problem contest
     * measured while fixing this, four statements were {@code application/pdf} and three were
     * {@code text/plain}. Code that assumed PDF rendered the text ones as a broken embed.
     */
    public record Statement(byte[] bytes, String contentType) {
        public boolean isEmpty() {
            return bytes == null || bytes.length == 0;
        }
    }

    /**
     * Fetches a statement, trying the routes this DOMjudge might serve it on.
     *
     * <h2>Why this probes rather than calling one route</h2>
     *
     * <p>There is no single answer across versions, and the version CPIntel was written against
     * is not the only one in use. {@code /contests/{cid}/problems/{id}/statement} is an API
     * route from a later release; on 8.0.0 it does not exist, the OpenAPI document lists no
     * statement route at all, and the problem object carries no href to one. Against such an
     * instance the old single-route call returned 404 for every problem, which surfaced to
     * contestants as "this problem has no statement attached" — a sentence that was true of
     * nothing except the route being wrong.
     *
     * <p>So the order below is API first, because it is the cheapest and the one newer
     * installations answer, then the team page, then the public page. The route that answers
     * is remembered, so only the first statement of a session pays for the probing; it is
     * logged at INFO, which together with {@code scripts/domjudge-probe.sh} is the first thing
     * to look at when statements do not appear.
     *
     * <p>The two web routes are not interchangeable. {@code /public/...} needs no credentials
     * but only serves problems in a contest the instance exposes publicly — measured on a live
     * instance, it answered for a finished public contest and 404'd for the running one, which
     * is the one that matters. {@code /team/...} answers for whatever the account may see, and
     * needs a signed-in session, because the web firewall refuses HTTP Basic. Hence
     * {@link DomjudgeWebSession}.
     */
    public Statement getStatement(DomjudgeCredentialStore.Stored as, String contestId,
                                  String problemId) {
        StatementRoute remembered = statementRoute.get();
        if (remembered != null) {
            Statement hit = tryStatementRoute(remembered, as, contestId, problemId);
            if (hit != null && !hit.isEmpty()) return hit;
            // The remembered route stopped answering — a different contest's visibility, or an
            // upgrade. Fall through and probe again rather than reporting nothing.
            statementRoute.set(null);
        }

        for (StatementRoute route : StatementRoute.values()) {
            Statement found = tryStatementRoute(route, as, contestId, problemId);
            if (found != null && !found.isEmpty()) {
                if (statementRoute.compareAndSet(null, route)) {
                    log.info("DOMjudge statements resolve on {} for this instance", route);
                }
                return found;
            }
        }

        log.debug("No statement for {}/{} on any known route", contestId, problemId);
        return null;
    }

    /** The places a DOMjudge build might publish a problem statement, cheapest first. */
    private enum StatementRoute { API, TEAM_PAGE, PUBLIC_PAGE }

    /** Which route answered last, so later problems skip the probing. */
    private final java.util.concurrent.atomic.AtomicReference<StatementRoute> statementRoute =
        new java.util.concurrent.atomic.AtomicReference<>();

    /** Which route resolved, for the admin screen and for support questions. */
    public String discoveredStatementRoute() {
        StatementRoute route = statementRoute.get();
        return route == null ? null : route.name();
    }

    private Statement tryStatementRoute(StatementRoute route, DomjudgeCredentialStore.Stored as,
                                        String contestId, String problemId) {
        try {
            return switch (route) {
                case API -> fetchStatement(client(as),
                    "/contests/" + contestId + "/problems/" + problemId + "/statement", null);
                case TEAM_PAGE -> {
                    String cookie = webSession.cookieFor(as);
                    if (cookie == null) yield null;
                    Statement first = fetchStatement(plainWebClient(),
                        "/team/problems/" + problemId + "/text",
                        DomjudgeWebSession.withContest(cookie, contestId));
                    if (first != null) yield first;
                    // A lapsed session redirects to /login, which reads as no statement.
                    // Worth exactly one retry on a fresh cookie before giving up on the route.
                    webSession.invalidate(as);
                    String retry = webSession.cookieFor(as);
                    yield retry == null ? null : fetchStatement(plainWebClient(),
                        "/team/problems/" + problemId + "/text",
                        DomjudgeWebSession.withContest(retry, contestId));
                }
                case PUBLIC_PAGE -> fetchStatement(plainWebClient(),
                    "/public/problems/" + problemId + "/text", null);
            };
        } catch (Exception e) {
            log.debug("Statement route {} did not answer for {}/{}: {}",
                route, contestId, problemId, e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * One route, returning null for anything that is not actually a document.
     *
     * <p>The redirect check is the one that matters. An unauthenticated request to a team page
     * answers {@code 302 → /login} with an HTML body, and a client that follows redirects would
     * cheerfully hand back the login page as a "statement" — a few kilobytes of HTML that
     * renders as a blank pane and looks exactly like a broken PDF. Redirects are therefore not
     * followed, and an HTML response is refused unless it is genuinely a statement rather than
     * an error page.
     */
    private Statement fetchStatement(WebClient client, String uri, String cookie) {
        WebClient.RequestHeadersSpec<?> request = client.get()
            .uri(uri)
            .accept(MediaType.APPLICATION_PDF, MediaType.TEXT_HTML, MediaType.TEXT_PLAIN,
                MediaType.ALL);
        if (cookie != null) request = request.header(HttpHeaders.COOKIE, cookie);

        var response = request
            .retrieve()
            .onStatus(status -> status.isError() || status.is3xxRedirection(),
                r -> reactor.core.publisher.Mono.empty())
            .toEntity(byte[].class)
            .block(Duration.ofSeconds(30));

        if (response == null || !response.getStatusCode().is2xxSuccessful()) return null;

        byte[] body = response.getBody();
        if (body == null || body.length == 0) return null;

        String contentType = mediaTypeOf(response.getHeaders());

        // A login page or an error page is HTML that is not a statement. PDFs and plain text
        // are taken as-is; HTML is only believed when nothing about it says "sign in".
        if (contentType.startsWith(MediaType.TEXT_HTML_VALUE)) {
            String text = new String(body, StandardCharsets.UTF_8);
            if (text.contains("name=\"_csrf_token\"") || text.contains("name=\"_password\"")) {
                return null;
            }
        }
        return new Statement(body, contentType);
    }

    /**
     * The media type of a response, as a string to pass on.
     *
     * <p>By the time this runs the header has already been through
     * {@link #repairMalformedContentType()}, so it parses — DOMjudge's unterminated {@code
     * name="prob-X.pdf} parameter was rewritten there, before the body was decoded, because
     * that is the only point early enough to stop WebClient throwing while it chose a decoder.
     *
     * <p>So this keeps the header whole rather than stripping it again. That matters for one
     * parameter: a text statement's {@code charset}. Reducing {@code text/plain;charset=UTF-8}
     * to {@code text/plain} would leave a statement with an accent in it to be decoded by
     * whatever the reader guessed.
     */
    private String mediaTypeOf(HttpHeaders headers) {
        String raw = headers.getFirst(HttpHeaders.CONTENT_TYPE);
        if (raw == null || raw.isBlank()) return MediaType.APPLICATION_OCTET_STREAM_VALUE;
        try {
            return MediaType.parseMediaType(raw).toString();
        } catch (Exception e) {
            // The repair filter should have made this impossible; a response that reaches here
            // is one it could not salvage, and octet-stream is the honest answer for bytes
            // whose type nothing can name.
            return MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }
    }
}
