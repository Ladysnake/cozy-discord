FROM gradle:7.3.3-jdk17-alpine as BUILD

WORKDIR /app

# set /app to be owned by gradle
RUN chown -R gradle:gradle /app

USER gradle

# copy build files
COPY . .

RUN gradle build

FROM openjdk:17-jdk-slim as DEPLOY

COPY --from=BUILD /app/build/libs/CozyDiscord-*-all.jar /usr/local/lib/CozyDiscord.jar

WORKDIR /bot
RUN mkdir /data

CMD java -jar /usr/local/lib/CozyDiscord.jar
