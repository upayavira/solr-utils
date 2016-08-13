#!/bin/bash

VERSION=${VERSION-latest}
REGISTRY=${REGISTRY/-""}

echo "Using registry $REGISTRY"
mvn package
docker build -t ${REGISTRY}solr:${VERSION} .
if [ "$1" = "push" ]; then
  echo "Pushing..."
  docker push ${REGISTRY}solr:${VERSION}
fi
