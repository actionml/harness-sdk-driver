# harness-client-cli

This tool uses the Java/Scala Client SDK for Harness to send events , input or queries, to a Harness server via the REST API. It has many tuning options but is simple when no experimentation is required.

## Install Prerequisites

* install `sbt`
    
    Debian/Ubuntu:
    
    ```
    echo "deb https://dl.bintray.com/sbt/debian /" | sudo tee -a /etc/apt/sources.list.d/sbt.list
    sudo apt-key adv --keyserver hkps://keyserver.ubuntu.com:443 --recv 2EE0EA64E40A89B84B2DF73499E82A75642AC823
    sudo apt-get update
    sudo apt-get install sbt
    ```
* install `jdk`
    
    Debian/Ubuntu:
    
    ```
    sudo add-apt-repository ppa:openjdk-r/ppa ; sudo apt-get update
    sudo apt-get install openjdk-8-jdk
    ```
## Get the Java Client SDK for Harness

This step will be unneeded as we get the Client SDK published to a place findable by sbt, in the maven-verse of JVM repos.

* install git or make sure it is available `git --version`  

* `sudo apt install maven`

* build the Java Client to install the lib for use in building the tool
    * `git clone https://github.com/actionml/harness-java-sdk.git`
    * `cd harness-java-sdk`
    * `mvn install`

## Build and Run the Tool
  
* `sbt compile stage`
* `./harness-events-cli.sh`
    
    Help:
    
    ```
    Slf4jLogger started
    Error: Missing option --engine-id
    Error: Missing option --file
    harness events 0.1
    Usage: harness-events-cli.sh [input|query] [options]
    
    Command: input
    
    Command: query
    
      -c, --max-concurrent <value>
                               Number of parallel connections
      -r, --requests-per-second <value>
                               Number of requests sent per second
      -e, --engine-id <value>  Engine id
      -h, --host <value>       Harness host
      -p, --port <value>       Harness port
      -f, --file <value>       Path to the file with events
      --factor <value>         Skip all events except one of factor. E.g. if factor is 10, then only 1 event of 10 will be sent.
    ```

## Usage

This tool is used to test the Java Client SDK for Harness and allows experimentation with performance tuning. For general use in transferring input from files to some Harness server, most options are not used.

To send a file that contains one event in JSON per line of text, do the following:

```
harness-events-cli.sh input \
   -f </path/to/file> \
   -e <some-engine-id> \
   -h <some-harness-address> \
   -c <some-number-of-connections>
```

To speed the transfer, specify as many parallel connections as possible. To send many files, concatenate them first into one file.