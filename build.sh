#!/bin/sh

VERSION=${VERSION-latest}
REGISTRY=${REGISTRY/-}

mvn package
docker build -t ${REGISTRY}solr:${VERSION} .
if [ "$1" = "push" ]; then
  docker push ${REGISTRY}solr:${VERSION}
fi
