# for OE build pipeline generate image, use in local dev or deploy in alphacloud for test.

FROM hub.xiaojukeji.com/linyu/didi-starrocks-dev:3.3.6

RUN yum install -y mysql net-tools

ENV STARROCKS_HOME=/opt/starrocks

RUN mkdir $STARROCKS_HOME
RUN mkdir $STARROCKS_HOME/fe
RUN mkdir $STARROCKS_HOME/be

ENV PATH=$STARROCKS_HOME/fe/bin:$STARROCKS_HOME/be/bin:$JAVA_HOME/bin:$PATH

WORKDIR $STARROCKS_HOME

COPY ./fe $STARROCKS_HOME/fe
COPY ./be $STARROCKS_HOME/be

COPY docker-entrypoint.sh /

RUN chmod +x /docker-entrypoint.sh
EXPOSE 9030

ENTRYPOINT ["/docker-entrypoint.sh"]

