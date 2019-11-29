FROM openjdk:8-jre-alpine3.8

ENV JAVA_HOME /usr/lib/jvm/java-1.8-openjdk
ENV PATH $PATH:$JAVA_HOME/bin

ENV NUM_OF_THREADS=2
ENV REQUESTS_PER_SECOND=100
ENV ENGINE_ID="test_ur"
ENV HARNESS_HOST="localhost"
ENV HARNESS_PORT=9090
ENV INPUT=true
ENV FILE_NAME="events.json"

RUN mkdir -p /app/lib/ && \
    adduser -Ds /bin/sh -h /app harness

COPY ./target/universal/stage/lib/* /app/lib/
COPY ./entrypoint.sh /app
WORKDIR /app
ENTRYPOINT [ "/app/entrypoint.sh" ]

USER harness:harness
