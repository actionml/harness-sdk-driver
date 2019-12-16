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
    or download from <https://www.scala-sbt.org/download.html>
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
    * `git clone https://github.com/actionml/harness-sdk-driver.git`
    * `cd harness-sdk-driver`
    * `mvn install`

## Build and Run the Tool
  
* `sbt compile stage`
* `./harness-load-test.sh`
    
    Help:
    
```
harness load test 0.2
Usage: harness-load-test.sh [input|query] [options]

Command: input

Command: query

  --entityType <value>     Value of 'entityType' field to be used to create search queries
  -c, --thread-pool-size <value>
                           Thread pool size
  -n, --num-of-threads <value>
                           Number of parallel threads
  -r, --max-requests-per-second <value>
                           Maximum number of requests sent per second
  -e, --engine-id <value>  Engine id
  -u, --uri <value>        Harness server URI
  -f, --file <value>       Path to the file with events. It can be a path to the file or directory. E.g. /tmp/event.json or /tmp. Directory means that all of it's files will be sent
  -v, --verbose            More info
  --factor <value>         Skip all events except one of factor. E.g. if factor is 10, then only 1 event of the random value from 1 to 10 will be sent.
  
  ```

## Usage

This tool is used to test the Java Client SDK for Harness and allows experimentation with performance tuning. For general use in transferring input from files to some Harness server, most options are not used.

To send a file that contains one event in JSON per line of text, do the following:

```
harness-events-cli.sh input \
   -f </path/to/file> \
   -e <some-engine-id> \
   -u <some-harness-uri> \
   -n <some-number-of-connections>
```

To speed the transfer, specify as many parallel connections as possible. To send many files, concatenate them first into one file.