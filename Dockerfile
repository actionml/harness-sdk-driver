FROM openjdk:8-jre-alpine3.8

ENV JAVA_HOME /usr/lib/jvm/java-1.8-openjdk
ENV PATH $PATH:$JAVA_HOME/bin

ENV NUM_OF_THREADS=2
ENV REQUESTS_PER_SECOND=100
ENV ENGINE_ID="hb_per_test"
ENV HARNESS_HOST="localhost"
ENV HARNESS_PORT=9090
ENV COMMAND=input
ENV FILE_NAME="events.json"
ENV FACTOR=10

RUN mkdir -p /app/lib/ && \
    adduser -Ds /bin/sh -h /app harness && \
    apk add --no-cache tini

COPY ./target/universal/stage/lib/* /app/lib/
COPY ./entrypoint.sh /app
COPY events.json /app/
WORKDIR /app
ENTRYPOINT ["/tini", "--", "/app/entrypoint.sh" ]

USER harness:harness
