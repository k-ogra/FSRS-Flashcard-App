FROM eclipse-temurin:25-jdk-noble AS build
WORKDIR /app
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw dependency:go-offline -q
RUN apt-get update && apt-get install -y curl && curl -o /app/global-bundle.pem https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem
COPY src src
RUN ./mvnw -ntp -B package -DskipTests

FROM eclipse-temurin:25-jre-noble
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
COPY --from=build /app/global-bundle.pem global-bundle.pem
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
