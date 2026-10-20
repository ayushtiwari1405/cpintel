package com.cpintel.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.filter.ForwardedHeaderFilter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The credentialed-CORS allowlist: what it accepts, what refuses a prod start, and how the
 * resulting policy treats the requests CPIntel actually receives.
 *
 * <p>The behaviour tests run the real configuration source behind {@link ForwardedHeaderFilter}
 * — what {@code forward-headers-strategy: framework} installs in prod — so the forwarded headers
 * nginx sends are read the way the deployed backend reads them.
 */
class CorsConfigTest {

    private static final String REFRESH = "/api/v1/auth/refresh";

    @RestController
    static class Endpoint {
        @PostMapping(REFRESH)
        String refresh() { return "ok"; }
    }

    private static CorsConfig config(String origins, String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        if (origins != null) env.setProperty(CorsConfig.PROPERTY, origins);
        return new CorsConfig(env);
    }

    private static MockMvc mvc(String origins, String... profiles) {
        return MockMvcBuilders.standaloneSetup(new Endpoint())
            .addFilters(new ForwardedHeaderFilter(),
                new CorsFilter(config(origins, profiles).corsConfigurationSource()))
            .build();
    }

    /** A request as nginx forwards it: plain http inside, the browser's view in the headers. */
    private static MockHttpServletRequestBuilder viaNginx(MockHttpServletRequestBuilder req,
                                                          String browserHostAndPort) {
        String hostOnly = browserHostAndPort.replaceFirst(":\\d+$", "");
        return req.header(HttpHeaders.HOST, hostOnly)            // proxy_set_header Host $host
            .header("X-Forwarded-Proto", "https")
            .header("X-Forwarded-Host", browserHostAndPort);     // proxy_set_header X-Forwarded-Host $http_host
    }

    @Nested
    @DisplayName("parsing the allowlist")
    class Parsing {

        @Test
        @DisplayName("empty, blank or unset means no other origin, in every profile")
        void emptyByDefault() {
            assertThat(CorsConfig.allowedOrigins(null, true)).isEmpty();
            assertThat(CorsConfig.allowedOrigins("", true)).isEmpty();
            assertThat(CorsConfig.allowedOrigins(" , ,", true)).isEmpty();
            assertThat(CorsConfig.allowedOrigins("", false)).isEmpty();
        }

        @Test
        @DisplayName("exact origins are kept in the form a browser sends them")
        void normalised() {
            assertThat(CorsConfig.allowedOrigins(
                "HTTPS://Other.Example.EDU:443, http://10.0.0.5:8080 ,https://other.example.edu",
                true))
                .containsExactly("https://other.example.edu", "http://10.0.0.5:8080");
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "*", "https://*.example.edu", "https://other.example.edu*",
            "null", "NULL",
            "ftp://other.example.edu", "file:///etc", "chrome-extension://abcdef", "javascript:x",
            "https://other.example.edu/", "https://other.example.edu/app",
            "https://other.example.edu?x=1", "https://other.example.edu#x",
            "https://user@other.example.edu", "https://user:pw@other.example.edu",
            "other.example.edu", "//other.example.edu", "https://", "https://bad host",
            "https://other.example.edu:notaport",
        })
        @DisplayName("prod refuses to start on anything that is not an exact http(s) origin")
        void prodRefuses(String entry) {
            assertThatThrownBy(() -> CorsConfig.allowedOrigins("https://ok.example.edu, " + entry, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Refusing to start")
                .hasMessageContaining("CPINTEL_CORS_ALLOWED_ORIGINS");
        }

        @Test
        @DisplayName("the refusal names every bad entry, not just the first")
        void namesEveryProblem() {
            assertThatThrownBy(() -> CorsConfig.allowedOrigins("*, null, https://x.example/", true))
                .hasMessageContaining("'*'").hasMessageContaining("'null'")
                .hasMessageContaining("'https://x.example/'");
        }

        @Test
        @DisplayName("outside prod a bad entry is dropped, never widened, and the rest still apply")
        void devDropsBadEntries() {
            assertThat(CorsConfig.allowedOrigins("*, https://*.lan, null, http://localhost:3000", false))
                .containsExactly("http://localhost:3000");
        }

        @Test
        @DisplayName("an invalid list fails the prod bean, so the context never starts")
        void prodBeanFails() {
            assertThatThrownBy(() -> config("*", "prod").corsConfigurationSource())
                .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("same origin, with the default empty list")
    class SameOrigin {

        @Test
        @DisplayName("behind nginx on 443")
        void nginxStandardPort() throws Exception {
            mvc("", "prod").perform(viaNginx(post(REFRESH), "cpintel.lan")
                    .header(HttpHeaders.ORIGIN, "https://cpintel.lan"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        }

        @Test
        @DisplayName("behind nginx on a non-standard port, which X-Forwarded-Host carries")
        void nginxOtherPort() throws Exception {
            mvc("", "prod").perform(viaNginx(post(REFRESH), "10.0.0.5:8443")
                    .header(HttpHeaders.ORIGIN, "https://10.0.0.5:8443"))
                .andExpect(status().isOk());
        }

        @Test
        @DisplayName("without X-Forwarded-Host a non-standard port looked foreign (why nginx sends it)")
        void otherPortWithoutForwardedHost() throws Exception {
            mvc("", "prod").perform(post(REFRESH)
                    .header(HttpHeaders.HOST, "10.0.0.5")
                    .header("X-Forwarded-Proto", "https")
                    .header(HttpHeaders.ORIGIN, "https://10.0.0.5:8443"))
                .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("through the Vite dev proxy with changeOrigin false, on the default profile")
        void viteDevProxy() throws Exception {
            // changeOrigin: false keeps the browser's Host, so the backend sees localhost:5173.
            mvc(null).perform(post(REFRESH)
                    .header(HttpHeaders.HOST, "localhost:5173")
                    .header(HttpHeaders.ORIGIN, "http://localhost:5173"))
                .andExpect(status().isOk());
        }

        @Test
        @DisplayName("with changeOrigin true the dev proxy would have been refused")
        void viteDevProxyRewritingHost() throws Exception {
            mvc(null).perform(post(REFRESH)
                    .header(HttpHeaders.HOST, "localhost:8080")
                    .header(HttpHeaders.ORIGIN, "http://localhost:5173"))
                .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("no Origin at all — the desktop app's main process, the helper, scripts")
        void noOrigin() throws Exception {
            mvc("", "prod").perform(viaNginx(post(REFRESH), "cpintel.lan"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        }
    }

    @Nested
    @DisplayName("other origins")
    class OtherOrigins {

        @ParameterizedTest
        @ValueSource(strings = {"https://evil.example", "https://sibling.lan", "null",
            "http://cpintel.lan", "https://cpintel.lan:8443"})
        @DisplayName("are refused by default: foreign, same-site sibling, null, other scheme or port")
        void refusedByDefault(String origin) throws Exception {
            mvc("", "prod").perform(viaNginx(post(REFRESH), "cpintel.lan")
                    .header(HttpHeaders.ORIGIN, origin))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
        }

        @Test
        @DisplayName("a refused preflight gets no permission headers")
        void preflightRefused() throws Exception {
            mvc("", "prod").perform(viaNginx(options(REFRESH), "cpintel.lan")
                    .header(HttpHeaders.ORIGIN, "https://evil.example")
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        }

        @Test
        @DisplayName("a listed origin is allowed, with credentials, and only that origin is echoed")
        void listedOriginAllowed() throws Exception {
            MockMvc m = mvc("https://other.example.edu:8443", "prod");
            m.perform(viaNginx(post(REFRESH), "cpintel.lan")
                    .header(HttpHeaders.ORIGIN, "https://other.example.edu:8443"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                    "https://other.example.edu:8443"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
            m.perform(viaNginx(post(REFRESH), "cpintel.lan")
                    .header(HttpHeaders.ORIGIN, "https://evil.example"))
                .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("a listed origin's preflight is answered")
        void listedOriginPreflight() throws Exception {
            mvc("https://other.example.edu", "prod").perform(viaNginx(options(REFRESH), "cpintel.lan")
                    .header(HttpHeaders.ORIGIN, "https://other.example.edu")
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization, Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://other.example.edu"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
        }
    }
}
