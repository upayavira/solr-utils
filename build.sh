#!/bin/bash

if [ -z $VERSION ]; then
  if [ "$1" = "push" ]; then
    VERSION=1.0.$BUILD_NUMBER
  else
    VERSION=latest
  fi
fi

REGISTRY=${REGISTRY-"docker.odoko.org"}

mvn package
docker build -t solr:${VERSION} .
if [ "$1" = "push" ]; then
  echo "Pushing..."
  docker tag solr:${VERSION} ${REGISTRY}/solr:${VERSION}
  docker push ${REGISTRY}solr:${VERSION}
fi
