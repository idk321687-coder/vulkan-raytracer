FROM gradle:8.14-jdk21 AS build
WORKDIR /workspace
COPY . .
RUN gradle --no-daemon :lobby:jar

FROM itzg/minecraft-server:java21
COPY --from=build /workspace/packages/lobby/build/libs/lobby-0.1.0.jar /plugins/AuroraLobby.jar
