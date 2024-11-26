#!/usr/bin/env bash

#local dev docker entrypoint
echo "start starrocks"

cd /opt/starrocks
mkdir meta
mkdir storage
mkdir log

/usr/sbin/sshd

# wait for ip init
LOCAL_IP=
while true ; do
  LOCAL_IP=`ifconfig -a|grep inet|grep -v 127.0.0.1|grep -v inet6|awk '{print $2}'|tr -d "addr:"​`
  echo $LOCAL_IP
  if [ -n "${LOCAL_IP}" ]; then
    echo "priority_networks = ${LOCAL_IP}/32" >> fe/conf/fe.conf
    echo "priority_networks = ${LOCAL_IP}/32" >> be/conf/be.conf
    break;
  fi
done

echo "meta_dir = ${STARROCKS_HOME}/meta" >> fe/conf/fe.conf
echo "storage_root_path = ${STARROCKS_HOME}/storage" >> be/conf/be.conf

fe/bin/start_fe.sh --daemon
be/bin/start_be.sh --daemon

while true ; do
  echo 'wait for add be...'
  sleep 1
  mysql -h 127.0.0.1 -P 9030 -e "ALTER SYSTEM ADD BACKEND '${LOCAL_IP}:9050'"
  last_return=$?
  if [[ $last_return == 0 ]]; then
    echo 'ADD BACKEND SUCCESS.'
    break
  fi
done

tail -20f /etc/hosts