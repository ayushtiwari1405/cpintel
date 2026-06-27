package com.cpintel.config;

import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MetricsConfig {

    @Bean
    public TimedAspect timedAspect(MeterRegistry registry) {
        return new TimedAspect(registry);
    }

    @Bean
    public JvmMemoryMetrics jvmMemoryMetrics() { return new JvmMemoryMetrics(); }

    @Bean
    public JvmGcMetrics jvmGcMetrics() { return new JvmGcMetrics(); }

    @Bean
    public JvmThreadMetrics jvmThreadMetrics() { return new JvmThreadMetrics(); }

    @Bean
    public ClassLoaderMetrics classLoaderMetrics() { return new ClassLoaderMetrics(); }

    @Bean
    public ProcessorMetrics processorMetrics() { return new ProcessorMetrics(); }
}
