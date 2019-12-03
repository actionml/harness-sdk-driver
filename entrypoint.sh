#!/bin/sh
set -e

## Defaults
#
: ${NUM_OF_THREADS}
: ${REQUESTS_PER_SECOND}
: ${ENGINE_ID:?must be set!}
: ${HARNESS_HOST:?must be set!}
: ${HARNESS_PORT}
: ${FILE_NAME:?must be set!}
: ${COMMAND:?must be set!}

java -cp "/app/lib/*" com.actionml.harness.client.JavaSdkApp ${COMMAND} -c ${NUM_OF_THREADS} -r ${REQUESTS_PER_SECOND} -e ${ENGINE_ID} -h ${HARNESS_HOST} -p ${HARNESS_PORT} -f ${FILE_NAME} --factor ${FACTOR}
