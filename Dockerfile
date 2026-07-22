# 빌드 스테이지 — 소스 복사 전에 빌드 스크립트만 먼저 복사해 의존성 다운로드 레이어를 캐시한다.
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN ./gradlew dependencies --no-daemon
COPY src ./src
RUN ./gradlew bootJar --no-daemon -x test

# 런타임 스테이지 — JDK가 아닌 JRE만 담아 이미지를 줄이고, 컨테이너 탈출 시 피해를 줄이기 위해 비 root로 실행한다.
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S teamd && adduser -S teamd -G teamd
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
USER teamd
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
