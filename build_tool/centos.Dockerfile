FROM starrocks/dev-env-centos7:3.3.6

RUN rm -f /opt/maven/conf/settings.xml
COPY settings.xml /opt/maven/conf

RUN echo "include=http://didiyum.sys.xiaojukeji.com/didiyum/repo.php" > /etc/yum.conf
RUN yum install -y openssh-server
RUN ssh-keygen -A

# starcache is from 3.4.1
RUN mv /var/local/thirdparty/installed/starcache /var/local/thirdparty/installed/starcache_v33
COPY starcache /var/local/thirdparty/installed/starcache

ENV STARROCKS_THIRDPARTY /var/local/thirdparty
ENV JAVA_HOME /usr/lib/jvm/java
ENV STARROCKS_GCC_HOME /opt/rh/gcc-toolset-10/root/usr

RUN echo 'export STARROCKS_THIRDPARTY=/var/local/thirdparty' >> /etc/profile
RUN echo 'export JAVA_HOME=/usr/lib/jvm/java' >> /etc/profile
RUN echo 'export STARROCKS_GCC_HOME=/opt/rh/gcc-toolset-10/root/usr' >> /etc/profile

# update didi hadoop version lib, current version is sr-3.3.6-003
ENV HADOOP_DIST_ADDR https://artifactory-new.intra.xiaojukeji.com/artifactory/product-dev-local/datainfra-hadoop/didi_hadoop/20241213110134_ba815562/datainfra-hadoop_didi_hadoop_parameter_centos7.2_20241213112231.tar.gz
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
