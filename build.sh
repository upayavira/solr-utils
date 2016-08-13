#!/bin/bash

VERSION=${VERSION-latest}

if [ -n "$REGISTRY" ]; then
  REGISTRY="${REGISTRY}/"
fi

echo "Using registry -$REGISTRY-"
mvn package
docker build -t ${REGISTRY}solr:${VERSION} .
if [ "$1" = "push" ]; then
  echo "Pushing..."
  docker push ${REGISTRY}solr:${VERSION}
fi
