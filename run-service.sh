#!/bin/bash

export ACTION=$1
echo "ACTION is $ACTION"

if [ -z $NODENAME ]; then
  # Guess that we might be on EC2:
  NODENAME=$(curl -s -m 2 http://169.254.169.254/latest/meta-data/local-ipv4 2>/dev/null)
fi

if [ $COLLECTION ]; then
  TODAY=$(date +%Y%m%d)  
  export COLLECTION=$(echo $COLLECTION | sed "s/TODAY/$TODAY/")
fi

if [ "$ACTION" = "run" ]; then
  if [ -z $NODENAME ]; then
    echo "No NODENAME provided. Exiting"
    exit
  fi
  ZOOKEEPER=$(ACTION=resolve java -jar /solr-libs/solr-utils.jar)
  echo "Connecting to Solr via ${ZOOKEEPER}"
  echo "Starting Solr..."
  /opt/solr/bin/solr -c -z ${ZOOKEEPER} -f -s /solr -h ${NODENAME}
else
echo "Connecting to Solr via ${ZOOKEEPER}"
  echo "Executing utility with ${ACTION}"
  java -jar /solr-libs/solr-utils.jar
fi
