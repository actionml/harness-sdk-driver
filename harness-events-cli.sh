#!/bin/sh
LIB=${LIB:lib}
java -Xmx7g -XX:+UseConcMarkSweepGC -cp "$LIB/*" com.actionml.harness.client.JavaSdkApp "$@"
