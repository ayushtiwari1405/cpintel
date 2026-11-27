package com.cpintel.config;

import com.cpintel.classrooms.ClassroomAccessInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final ClassroomAccessInterceptor classroomAccess;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new NulCharacters.Parameters());
        registry.addInterceptor(classroomAccess).addPathPatterns("/api/v1/admin/**");
    }

    /**
     * Request bodies are read by a copy of the application's JSON mapper that also refuses NUL.
     * The converter is replaced, not altered: the instance it replaces is shared with the HTTP
     * clients that read what other servers send.
     */
    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        converters.replaceAll(converter -> {
            if (!(converter instanceof MappingJackson2HttpMessageConverter json)) return converter;
            var strict = new MappingJackson2HttpMessageConverter(
                json.getObjectMapper().copy().registerModule(NulCharacters.jsonModule()));
            strict.setSupportedMediaTypes(json.getSupportedMediaTypes());
            return strict;
        });
    }
}
