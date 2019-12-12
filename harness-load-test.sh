#!/bin/sh
LIB=${LIB:lib}
java -Xmx1g -cp "$LIB/*" com.actionml.harness.client.LoadTest "$@"
