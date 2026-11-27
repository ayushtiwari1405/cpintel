package com.cpintel.config;

import com.cpintel.exception.ApiException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.deser.std.StdScalarDeserializer;
import com.fasterxml.jackson.databind.deser.std.StringDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.Arrays;

/**
 * Refuses the NUL character in anything a client sends.
 *
 * <p>PostgreSQL cannot store it in a text value, so a name, a search or a sign-in identifier
 * carrying one failed at the database and came back as a 500, the last of them without anybody
 * signing in. Nothing legitimate contains it. Only NUL is refused: tabs and newlines belong in
 * source code and statements.
 *
 * <p>For request bodies and parameters only. What the judge and the other platforms send back is
 * read by different mappers, and is theirs to say.
 */
public final class NulCharacters {

    private static final char NUL = 0;
    private static final String MESSAGE = "The request contains a NUL character.";

    private NulCharacters() {}

    private static boolean contains(String value) {
        return value != null && value.indexOf(NUL) >= 0;
    }

    /** Makes a JSON mapper refuse any string holding a NUL. */
    static Module jsonModule() {
        return new SimpleModule("cpintel-no-nul").addDeserializer(String.class, new Strings());
    }

    private static final class Strings extends StdScalarDeserializer<String> {

        Strings() {
            super(String.class);
        }

        @Override
        public String deserialize(JsonParser parser, DeserializationContext context)
                throws IOException {
            String value = StringDeserializer.instance.deserialize(parser, context);
            if (contains(value)) return context.reportInputMismatch(String.class, MESSAGE);
            return value;
        }
    }

    /** The same rule for query and form parameters, names included. */
    static final class Parameters implements HandlerInterceptor {

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                                 Object handler) {
            boolean found = request.getParameterMap().entrySet().stream()
                .anyMatch(entry -> contains(entry.getKey())
                    || Arrays.stream(entry.getValue()).anyMatch(NulCharacters::contains));
            if (found) throw ApiException.badRequest(MESSAGE);
            return true;
        }
    }
}
