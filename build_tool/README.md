> cd %{STARROCKS_HOME}/build_tool

## login hub.xiaojukeji.com
```
docker login hub.xiaojukeji.com
```
## build image
```
docker build --no-cache --platform=linux/amd64 -f base.Dockerfile -t hub.xiaojukeji.com/linyu/didi-starrocks-dev:3.3.6-002 .
```

## push image to hub.xiaojukeji.com
```
docker push hub.xiaojukeji.com/linyu/didi-starrocks-dev:3.3.6-002
```