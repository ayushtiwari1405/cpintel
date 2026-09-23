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

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S cpintel && adduser -S cpintel -G cpintel
RUN apk add --no-cache curl

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
