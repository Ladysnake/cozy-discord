FROM gradle:7.6-jdk17 as BUILD

WORKDIR /home/gradle/app

ENV GRADLE_USER_HOME=/home/gradle/.gradle

# copy build files
COPY . .

RUN gradle build --no-daemon

#------------------------------------------------

FROM openjdk:17-jdk-alpine as DEPLOY

WORKDIR /bot

RUN mkdir /data

COPY --from=BUILD /home/gradle/app/build/libs/CozyDiscord-*-all.jar /usr/local/lib/CozyDiscord.jar

ENTRYPOINT ["java", "-XX:MinRAMPercentage=50", "-XX:MaxRAMPercentage=90", "-XshowSettings:vm", "-jar","/usr/local/lib/CozyDiscord.jar"]
