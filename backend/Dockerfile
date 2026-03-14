FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN apt-get update && apt-get install -y maven && \
    mvn clean package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
RUN curl -Lo /app/dd-java-agent.jar https://dtdg.co/latest-java-tracer && chmod 644 /app/dd-java-agent.jar
EXPOSE 8080
ENTRYPOINT ["java", "-javaagent:/app/dd-java-agent.jar", "-Dspring.profiles.active=prod", "-Xmx768m", "-jar", "app.jar"]
