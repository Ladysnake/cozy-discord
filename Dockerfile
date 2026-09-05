FROM eclipse-temurin:17-jdk-alpine

WORKDIR /bot

VOLUME /data

COPY ./build/libs/CozyDiscord-*-all.jar /usr/local/lib/CozyDiscord.jar

ENTRYPOINT ["java", "-XX:MinRAMPercentage=50", "-XX:MaxRAMPercentage=90", "-XshowSettings:vm", "-jar","/usr/local/lib/CozyDiscord.jar"]
