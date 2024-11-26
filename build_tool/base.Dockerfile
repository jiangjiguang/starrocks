FROM starrocks/dev-env-centos7:3.3.6

RUN rm -f /opt/maven/conf/settings.xml
COPY settings.xml /opt/maven/conf

RUN echo "include=http://didiyum.sys.xiaojukeji.com/didiyum/repo.php" > /etc/yum.conf
RUN yum install -y openssh-server
RUN ssh-keygen -A

ENV STARROCKS_THIRDPARTY /var/local/thirdparty
ENV JAVA_HOME /usr/lib/jvm/java
ENV STARROCKS_GCC_HOME /opt/gcc/usr

RUN echo 'export STARROCKS_THIRDPARTY=/var/local/thirdparty' >> /etc/profile
RUN echo 'export JAVA_HOME=/usr/lib/jvm/java' >> /etc/profile
RUN echo 'export STARROCKS_GCC_HOME=/opt/gcc/usr' >> /etc/profile

# update didi hadoop version lib, current version is sr-3.3.6-002
ENV HADOOP_DIST_ADDR https://artifactory-new.intra.xiaojukeji.com/artifactory/product-dev-local/datainfra-hadoop/didi_hadoop/20241210102523_7cdc4493/datainfra-hadoop_didi_hadoop_parameter_centos7.2_20241210110212.tar.gz
ENV TP_INSTALL_DIR /var/local/thirdparty/installed

RUN mkdir /tmp/hadoop-tmp && \
    cd /tmp/hadoop-tmp && \
    wget -O didi_hadoop.tar.gz $HADOOP_DIST_ADDR && \
    tar -zxvf didi_hadoop.tar.gz && \
    cd output && \
    tar -zxvf hadoop-*.tar.gz && \
    rm -f *.tar.gz && \
    rm -rf $TP_INSTALL_DIR/hadoop && \
    mv hadoop* $TP_INSTALL_DIR/hadoop && \
    rm -f $TP_INSTALL_DIR/include/hdfs/hdfs.h && \
    cp $TP_INSTALL_DIR/hadoop/include/hdfs.h $TP_INSTALL_DIR/include/hdfs && \
    rm -f $TP_INSTALL_DIR/lib/libhdfs.a && \
    cp $TP_INSTALL_DIR/hadoop/lib/native/libhdfs.a $TP_INSTALL_DIR/lib && \
    cd /tmp && \
    rm -rf /tmp/hadoop-tmp

CMD ["/usr/sbin/sshd", "-D"]
