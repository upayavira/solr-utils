#!/bin/bash

function cleanup() {
  docker rm -f zookeeper solr utils
}

function solr_utils {
  CMD=$1
  ZK=$2
  shift 2
  ENVVARS=$*
  docker rm utils
  echo docker run $ENVVARS -e ZOOKEEPER=$ZK --name utils ${SOLR_IMAGE} ${CMD}
  docker run $ENVVARS -e CONFIG_NAME=${CONFIG_NAME} -e ZOOKEEPER=$ZK --name utils ${SOLR_IMAGE} ${CMD}
}

if [[ $# = 0 ]]; then
  BUILD=true
  START=true
  CLEANUP=true
else
  while [[ $# > 0 ]]; do
    KEY=$1
    echo $KEY
    case $KEY in
      build)
        BUILD=true ;;
      start)
        START=true ;;
      cleanup)
        CLEANUP=true ;;
      *)
        echo "Unknown action $KEY"
        exit 1
    esac
    shift
  done
fi

if [ -z "$ZOOKEEPER" ]; then
  ZOOKEEPER=192.168.99.100
fi

if [ -z "${NODENAME}" ]; then
  NODENAME=192.168.99.100
fi

if [ -z ${CONFIG_NAME} ]; then
  CONFIG_NAME=basic
fi

if [ -z "$BUILD_NUMBER" ]; then
  VERSION=latest
else
  VERSION=1.0.${BUILD_NUMBER}
fi
echo "Building version $VERSION"

SOLR_IMAGE=odoko-solr:${VERSION}

if [ -n "$BUILD" ]; then
  echo "Building Solr library, version ${VERSION}..."
  mvn package

  echo
  echo "Building Solr Docker Image..."
  docker build -t ${SOLR_IMAGE} . 
fi

if [ -n "$START" ]; then
  cleanup

  # start ZooKeeper
  docker run -d -p 2181:2181 --name zookeeper jplock/zookeeper
  sleep 1

  # create ZooKeeper chroot for /solr and upload solr/xml
  solr_utils create-chroot ${ZOOKEEPER} -e CHROOT=/solr
  solr_utils upload-solrxml ${ZOOKEEPER} -e CHROOT=/solr

  # start Solr now that chroot is created
  echo "STARTING ${SOLR_IMAGE}"
  docker run -d -p 8983:8983 -e NODENAME=${NODENAME} -e ZOOKEEPER=${ZOOKEEPER} --name solr ${SOLR_IMAGE}
  sleep 3

  # upload assets configs to ZooKeeper
  solr_utils upload ${ZOOKEEPER}
  
  # Create collection now that configs uploaded and Solr started
  COLLECTION=collection
  solr_utils create ${ZOOKEEPER} -e COLLECTION=$COLLECTION
  solr_utils create-alias ${ZOOKEEPER} -e COLLECTION=$COLLECTION -e ALIAS=assets 

fi

if [ -n "$CLEANUP" ]; then
  cleanup
fi
