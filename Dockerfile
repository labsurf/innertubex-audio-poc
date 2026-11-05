FROM gradle:9.8.2-jdk21 AS build
WORKDIR /app
COPY . .
RUN gradle --no-daemon installDist

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/install/innertubex-audio-poc/ ./
EXPOSE 10000
ENTRYPOINT ["/app/bin/innertubex-audio-poc"]
CMD ["serve"]
