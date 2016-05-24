Docker Utilities for Solr
=========================
This project contains code to make interacting with Solr in a Docker
environment much easier.

Tasks such as uploading configuration files, creating collections
and adding replicas can all be done by starting short-running
containers.

Usage
=====
Expect a fuller explanation to come.

In the meantime, on a Mac, this command should do stuff:

    NODENAME=192.168.99.100 ./start.sh build start

After which, you should be able to see Solr on
http://192.168.99.100:8983/solr

On Linux, this should work:

    NODENAME=localhost ./start.sh build start

You should find Solr at:

http://localhost:8983/solr
