#!/bin/bash

if [[ $# = 0 ]]; then
  echo "$0 [build] [push] [start] [cleanup]"
  exit 1
else
  ARGS=$*
fi

if [ -z "$ZOOKEEPER" ]; then   ZOOKEEPER=192.168.99.100; fi
if [ -z "${NODENAME}" ]; then  NODENAME=192.168.99.100; fi
if [ -z ${CONFIG_NAME} ]; then CONFIG_NAME=basic; fi

if [ -z "$BUILD_NUMBER" ]; then
  VERSION=latest
else
  VERSION=1.0.${BUILD_NUMBER}
fi
echo "Building version $VERSION"

SOLR_IMAGE=docker.odoko.org/solr:${VERSION}

for ARG in $ARGS; do
    case $ARG in
      build)
        mvn package
        docker build -t ${SOLR_IMAGE} .
        ;;
      push)
        docker push ${SOLR_IMAGE}
	;;
      start)
        ust up solrcloud local
	;;
      cleanup)
        ust rm solrcloud local
	;;
      *)
        echo "Unknown action $ARG"
        exit 1
    esac
done

