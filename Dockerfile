FROM gradle:7.6-jdk17 as BUILD

WORKDIR /app

ENV GRADLE_USER_HOME=/home/gradle/.gradle

# copy build files
COPY . .

RUN gradle build

FROM openjdk:17-jdk-slim as DEPLOY

COPY --from=BUILD /app/build/libs/CozyDiscord-*-all.jar /usr/local/lib/CozyDiscord.jar

WORKDIR /bot
RUN mkdir /data

CMD java -jar /usr/local/lib/CozyDiscord.jar
