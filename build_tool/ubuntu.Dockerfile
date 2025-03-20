FROM starrocks/dev-env-ubuntu:3.3.6

RUN rm -f /etc/maven/settings.xml
COPY settings.xml /etc/maven/settings.xml

RUN sed -i -e 's/^APT/# APT/' -e 's/^DPkg/# DPkg/' /etc/apt/apt.conf.d/docker-clean

RUN apt-get update && apt install -y openssh-server && ssh-keygen -A && \
    mkdir /var/run/sshd && chmod 0755 /var/run/sshd && echo 'PermitRootLogin yes' >> /etc/ssh/sshd_config

# starcache is from 3.4.1
RUN mv /var/local/thirdparty/installed/starcache /var/local/thirdparty/installed/starcache_v33
COPY starcache /var/local/thirdparty/installed/starcache

COPY clang+llvm-18.1.8-x86_64-linux-gnu-ubuntu-18.04 /root/clang-18.1.8

# update didi hadoop version lib, current version is sr-3.3.6-002
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

RUN echo 'export JAVA_HOME=/lib/jvm/default-java' >> /etc/profile && \
    echo 'export STARROCKS_THIRDPARTY=/var/local/thirdparty' >> /etc/profile && \
    echo 'export STARROCKS_LLVM_HOME=/root/clang-18.1.8' >> /etc/profile && \
    cd /usr/bin/ && rm -f lld && rm -f ld.lld && ln -s /root/clang-18.1.8/bin/lld lld && ln -s /root/clang-18.1.8/bin/ld.lld ld.lld

RUN ln -fs /bin/bash /bin/sh && chmod +rx /root && apt install -y libssl-dev libncurses5 libncursesw5

EXPOSE 22

CMD ["/usr/sbin/sshd", "-D"]

