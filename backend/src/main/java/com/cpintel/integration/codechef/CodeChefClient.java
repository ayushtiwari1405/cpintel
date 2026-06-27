package com.cpintel.integration.codechef;

import com.cpintel.exception.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
@Slf4j
public class CodeChefClient {

    @Value("${cpintel.platforms.codechef.base-url}")
    private String baseUrl;

    @Value("${cpintel.platforms.codechef.rate-limit-ms}")
    private long rateLimitMs;

    private static final Pattern RATING_PATTERN =
        Pattern.compile("\"rating\"\\s*:\\s*\"?(\\d+)\"?");

    public CcModels.UserProfile getUserProfile(String username) {
        log.debug("Scraping CC profile for: {}", username);
        try {
            Thread.sleep(rateLimitMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        try {
            Document doc = Jsoup.connect(baseUrl + "/users/" + username)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")
                .timeout(15000)
                .followRedirects(true)
                .get();

            // CodeChef renders rating inside a div with class 'rating-number'
            Element ratingEl = doc.selectFirst("div.rating-number");
            Element nameEl   = doc.selectFirst("li.user-country-name, .user-details-container h1");
            Element countryEl = doc.selectFirst(".user-country-name");

            if (ratingEl == null) {
                // Page exists but no rating data structure found — likely invalid/team handle
                throw ApiException.notFound("CodeChef user not found or has no rating: " + username);
            }

            Integer currentRating = parseInt(ratingEl.text());

            Integer highestRating = null;
            Element highestEl = doc.selectFirst("small.rating-number, .rating-header small");
            if (highestEl != null) {
                Matcher m = Pattern.compile("(\\d+)").matcher(highestEl.text());
                if (m.find()) highestRating = Integer.parseInt(m.group(1));
            }

            Integer globalRank = null;
            Element rankEl = doc.selectFirst(".rating-ranks ul li a strong");
            if (rankEl != null) globalRank = parseInt(rankEl.text());

            CcModels.UserProfile profile = new CcModels.UserProfile();
            profile.setUsername(username);
            profile.setName(nameEl != null ? nameEl.text() : username);
            profile.setCountry(countryEl != null ? countryEl.text() : null);
            profile.setCurrentRating(currentRating);
            profile.setHighestRating(highestRating != null ? highestRating : currentRating);
            profile.setGlobalRank(globalRank);

            return profile;

        } catch (IOException e) {
            log.warn("CC scrape IO error for {}: {}", username, e.getMessage());
            throw ApiException.notFound("CodeChef user not found: " + username);
        }
    }

    private Integer parseInt(String text) {
        if (text == null) return null;
        Matcher m = Pattern.compile("(\\d+)").matcher(text.replace(",", ""));
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    public boolean handleExists(String username) {
        try {
            getUserProfile(username);
            return true;
        } catch (Exception e) {
            log.warn("CC handle check failed for {}: {}", username, e.getMessage());
            return false;
        }
    }
}
