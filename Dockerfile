FROM solr:6.3.0

USER root
RUN mkdir /solr-libs /solr && \
    chown -R solr /solr

COPY /target/solr-plugins-1.0.0.jar /solr-libs
COPY /target/solr-utils.jar /opt/solr/
ADD /src/main/solr-config/ /opt/solr/configs/
ADD run-service.sh /opt/solr/

ENTRYPOINT ["./run-service.sh"]
WORKDIR /opt/solr
USER solr
CMD [ "run" ]
