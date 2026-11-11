FROM eclipse-temurin:17-jdk AS build

WORKDIR /app

COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .

RUN chmod +x mvnw
RUN ./mvnw dependency:go-offline

COPY src src

RUN ./mvnw clean package -DskipTests


FROM eclipse-temurin:17-jre

# Run as an unprivileged user so a compromised app cannot modify the system.
RUN groupadd --system app && useradd --system --gid app --home-dir /app --no-create-home app

WORKDIR /app

COPY --from=build --chown=app:app /app/target/*.jar app.jar

# The app writes optional debug files and the local H2 database under its working directory.
RUN chown app:app /app

USER app

EXPOSE 10000

ENTRYPOINT ["sh", "-c", "java -Dserver.port=${PORT:-10000} -jar /app/app.jar"]
