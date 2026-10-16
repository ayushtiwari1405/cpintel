# Maven comes with the image rather than through mvnw: the wrapper downloads its Maven
# distribution from Maven Central on every uncached build, so a transient 404 there failed the
# whole image build. The major/minor here should track distributionUrl in
# .mvn/wrapper/maven-wrapper.properties, which CI's test job still uses.
FROM maven:3.9-eclipse-temurin-21-alpine AS builder
WORKDIR /app

COPY pom.xml ./
RUN mvn -B dependency:go-offline -q

COPY src ./src
RUN mvn -B clean package -DskipTests -q

# ── the code runner ─────────────────────────────────────────────────────────
# Same jar, different entry point (com.cpintel.runner.RunnerServer): compiles and runs users'
# code for the Run button, and nothing else. Its own image because it needs the toolchains and
# bubblewrap, and because it must carry none of the backend's configuration. Built with
# `--target runner`; see the runner service in docker-compose.yml for how it is locked down.
#
# Ubuntu rather than Alpine so programs build against glibc and a current g++, as they will on
# the judge — musl differs in small ways that would make Run and Submit disagree.
FROM eclipse-temurin:21-jre-noble AS runner
RUN apt-get update \
 && apt-get install -y --no-install-recommends bubblewrap gcc g++ python3 time tini \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --uid 10001 --no-create-home --shell /usr/sbin/nologin runner
# <bits/stdc++.h>, precompiled beside the header itself, where g++ looks for it unasked. Nearly
# every competitive solution includes it, and parsing it is most of a compile: 2.6 s to 0.7 s
# on the load test's cores, which is the runner's throughput when a room presses Run at the
# bell. Built with CppRuntime's flags; a build whose flags differ ignores it and compiles from
# the header as before, so it can only make a run faster, never different.
RUN h="$(echo '#include <bits/stdc++.h>' | g++ -std=c++20 -x c++ -M - | tr ' ' '\n' | grep 'bits/stdc++\.h$')" \
 && g++ -std=c++20 -O2 -m64 -x c++-header "$h" -o "$h.gch"
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
USER 10001
EXPOSE 8090
# tini as PID 1, so the JVM is not. Every sandbox leaves a process for PID 1 to reap: bubblewrap's
# outer process exits as soon as it has the run's exit code, before its inner one (PID 1 of the
# sandbox) has exited, and that one is handed to the container's PID 1. The JVM never waits for
# children it did not start, so each became a zombie holding a pid: one per compile and per test,
# and at the container's 1024 every run failed. -s keeps it reaping under `docker run --init`.
ENTRYPOINT ["/usr/bin/tini", "-s", "--", \
  "java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=15.0", \
  "-Dloader.main=com.cpintel.runner.RunnerServer", \
  "-cp", "app.jar", \
  "org.springframework.boot.loader.launch.PropertiesLauncher"]

# ── the backend (default target) ────────────────────────────────────────────
# glibc, not Alpine: password hashing is argon2-jvm, which loads a native libargon2 built for
# glibc through JNA. On musl that library segfaults the JVM the first time anybody signs in.
FROM eclipse-temurin:21-jre-noble
WORKDIR /app

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system cpintel && useradd --system --gid cpintel --no-create-home cpintel

COPY --from=builder /app/target/*.jar app.jar
RUN chown cpintel:cpintel app.jar

USER cpintel
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-XX:+UseG1GC", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-Dspring.profiles.active=prod", \
  "-jar", "app.jar"]
