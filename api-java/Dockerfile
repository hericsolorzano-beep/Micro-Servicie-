# Imagen multietapa: compila con Maven, ejecuta solo con el JRE.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Primero solo el pom.xml. Es la capa que Maven cachea mejor: mientras las
# dependencias no cambien, el "mvn dependency:go-offline" no se repite.
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
# Empaqueta en una sola capa: el JAR final no depende de nada ya compilado.
RUN mvn -B clean package -DskipTests

# ---------------------------------------------------------------------------

FROM eclipse-temurin:21-jre
WORKDIR /app

# Usuario sin privilegios. Si alguien explota el contenedor, no encuentra
# un shell de root esperando.
# --user-group crea el grupo con el MISMO nombre que el usuario. Sin esta
# opcion, useradd crea el grupo "spring" pero "app" no existiria, y el
# COPY --chown de abajo fallaria al construir.
RUN useradd --system --uid 1001 --user-group --create-home spring
USER spring:spring

# Copia desde la etapa de build, no desde el contexto: la imagen final no
# lleva ni Maven ni el codigo fuente.
COPY --from=build --chown=spring:spring /build/target/microservicio-api-1.0.0.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]