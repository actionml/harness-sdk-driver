#!/bin/sh
LIB=${LIB:lib}
java -cp "$LIB/*" com.actionml.harness.client.LoadTest "$@"
