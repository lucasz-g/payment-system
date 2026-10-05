# =============================================================================
# Dockerfile do order-service
#
# Build em dois estágios (multi-stage):
#   1. "build"   -> imagem com JDK + Maven: compila o projeto e gera o .jar
#   2. "runtime" -> imagem enxuta só com JRE: roda a aplicação
#
# Assim a imagem final não carrega Maven, código-fonte nem o JDK completo,
# ficando bem menor e com menos superfície de ataque.
#
# Uso isolado:   docker build -t order-service ./order-service
# Via compose:   docker compose up --build order-service
# =============================================================================


# -----------------------------------------------------------------------------
# Estágio 1: build
# -----------------------------------------------------------------------------
# Imagem oficial do Maven já com o Temurin 21 (mesma versão do <java.version>
# do pom.xml). Usamos o Maven da imagem em vez do ./mvnw para evitar problemas
# de quebra de linha (CRLF) do script quando o repositório é clonado no Windows.
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

# Copiamos primeiro SÓ o pom.xml e baixamos as dependências.
# O Docker faz cache por camada: enquanto o pom.xml não mudar, esta etapa
# (a mais demorada) é reaproveitada, e alterações apenas no código-fonte
# não disparam um novo download de todas as dependências.
#
# O --mount=type=cache mantém o repositório local do Maven (~/.m2) entre
# builds, acelerando ainda mais (requer BuildKit, padrão no Docker moderno).
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q dependency:go-offline

# Agora sim copiamos o código-fonte e empacotamos.
# -DskipTests: os testes devem rodar na pipeline de CI, não no build da imagem
# (além disso, testes de integração precisariam de Postgres/RabbitMQ aqui).
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q package -DskipTests \
    && cp target/*.jar app.jar

# Extrai o jar em camadas (recurso do Spring Boot, "jarmode=tools"):
#   dependencies/           -> libs de terceiros (mudam raramente)
#   spring-boot-loader/     -> classes do launcher do Spring Boot
#   snapshot-dependencies/  -> libs SNAPSHOT (mudam com mais frequência)
#   application/            -> nosso código e resources (mudam sempre)
# Copiando cada pasta como uma camada separada no estágio final, um novo
# deploy só precisa enviar/baixar a camada "application", que é pequena.
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted


# -----------------------------------------------------------------------------
# Estágio 2: runtime
# -----------------------------------------------------------------------------
# Apenas o JRE (sem compilador) sobre Alpine: imagem final bem mais leve.
FROM eclipse-temurin:21-jre-alpine AS runtime

WORKDIR /app

# Boa prática de segurança: não rodar a aplicação como root.
# Criamos um usuário/grupo de sistema dedicado ("spring").
RUN addgroup -S spring && adduser -S spring -G spring

# Copiamos as camadas na ordem da que MENOS muda para a que MAIS muda,
# maximizando o reaproveitamento de cache entre versões da imagem.
COPY --from=build --chown=spring:spring /app/extracted/dependencies/ ./
COPY --from=build --chown=spring:spring /app/extracted/spring-boot-loader/ ./
COPY --from=build --chown=spring:spring /app/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=spring:spring /app/extracted/application/ ./

USER spring

# Opções da JVM. JAVA_TOOL_OPTIONS é lida automaticamente pela JVM, então
# pode ser sobrescrita no docker-compose sem mexer no ENTRYPOINT.
#   MaxRAMPercentage=75 -> heap usa até 75% da memória limite do container
#                          (a JVM respeita os limites do cgroup/container)
#   ExitOnOutOfMemoryError -> em caso de OOM o processo morre e o Docker
#                          pode reiniciá-lo, em vez de ficar num estado zumbi
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

# Porta HTTP padrão do Spring Boot (mapeada no docker-compose como 8080:8080).
# EXPOSE é apenas documentação; quem publica a porta é o "ports" do compose.
EXPOSE 8080

# Verifica a saúde da aplicação via Spring Boot Actuator (já está no pom.xml).
# O wget vem no BusyBox do Alpine. start-period dá tempo para o Flyway rodar
# as migrations e o contexto do Spring subir antes de contar as falhas.
HEALTHCHECK --interval=15s --timeout=5s --start-period=40s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health || exit 1

# Forma "exec" (array JSON): a JVM vira o PID 1 e recebe diretamente o SIGTERM
# do "docker stop", permitindo um graceful shutdown do Spring.
# Como o jar foi extraído, iniciamos pelo JarLauncher do Spring Boot.
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
