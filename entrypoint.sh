#!/bin/sh
set -e

## Defaults
#
: ${NUM_NUM_OF_THREADS}
: ${REQUESTS_PER_SECOND}
: ${ENGINE_ID:?must be set!}
: ${HARNESS_HOST:?must be set!}
: ${HARNESS_PORT}
: ${INPUT}
: ${FILE_NAME:?must be set!}

java -cp "/app/lib/*" com.actionml.harness.client.JavaSdkApp ${NUM_NUM_OF_THREADS} ${REQUESTS_PER_SECOND} ${ENGINE_ID} ${HARNESS_HOST} ${HARNESS_PORT} ${INPUT} ${FILE_NAME}
