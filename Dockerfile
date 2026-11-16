# Base images are pinned to exact builds; Dependabot opens a pull request when newer ones come out.
FROM eclipse-temurin:17-jdk@sha256:5465815640e10433422217335e15a523a3e57021a1f2ae0fc3849380d237979f AS build

WORKDIR /app

COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .

RUN chmod +x mvnw
RUN ./mvnw dependency:go-offline

COPY src src

RUN ./mvnw clean package -DskipTests


FROM eclipse-temurin:17-jre@sha256:0b6521287f850ec1c4b31c9074c5a1ae1d67c5922c3fefd21065c7c32e244d4a

# Run as an unprivileged user so a compromised app cannot modify the system.
RUN groupadd --system app && useradd --system --gid app --home-dir /app --no-create-home app

WORKDIR /app

COPY --from=build --chown=app:app /app/target/*.jar app.jar

# The app writes optional debug files and the local H2 database under its working directory.
RUN chown app:app /app

USER app

EXPOSE 10000

ENTRYPOINT ["sh", "-c", "java -Dserver.port=${PORT:-10000} -jar /app/app.jar"]
