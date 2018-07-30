## Khipu: A Scala/Akka implementation of the Ethereum protocol

Khipu is developed and maintained by khipu.io Team.
It is built on earlier work on Mantis by [Grothendieck Team](https://iohk.io/projects/ethereum-classic/).

The major researches of Khipu so far:

  - Try to execute transactions of the same block as in parallel as possible. Average 80% transactions of the same block could be executed in parallel currently
  - A dedicated storage engine special for blockchain, which is developed based on Kafka's log engine. It's designed to perform only 1 disk I/O at most for 99.6% random read

### Status - Alpha Release 0.1.0-alpha

This version of the code supports

  - Peer discovery
  - Fast sync (download a recent state trie snapshot and all blocks, this is the default behaviour)
  - Regular sync (download and execute every transaction in every block in the chain), this will be enabled once fast sync finished

Features to be done

  - Reduce disk usage
  - CPU mining
  - Execute transactions in parallel in mining
  - JSON RPC API (useful for console and Mist integration)
  - Morden testnet and private network
  - Unit tests

Minimum requirements to run Khipu

  - 16G RAM, 250G disk space (SSD is preferred, although HDD is okay)
  - Under regular sync mode, if you restart Khipu, it may need 2~3 minutes (SSD), or 10 minutes (HDD) to load the storage index

### Installation and Running

The latest release can be downloaded from [here](https://github.com/khipu-io/khipu/releases)

Running from command line:


```
unzip khipu-eth-0.1.0.zip
cd khipu-eth-0.1.0/bin
./khipu-eth
```

#### Prerequisites to build

- JDK 1.8 (download from [java.com](http://www.java.com))
- sbt ([download sbt](http://www.scala-sbt.org/download.html))

#### Build the client

As an alternative to downloading the client, build the client from source.

```
git clone https://github.com/khipu-io/khipu.git
cd khipu
sbt khipu-eth/dist
```

## License

Khipu is licensed under the MIT License (found in the COPYING file in the root directory).

