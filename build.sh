#!/bin/sh

if [ -z $VERSION ]; then
  VERSION=latest
fi

mvn package
docker build -t docker.odoko.org/solr:${VERSION} .
