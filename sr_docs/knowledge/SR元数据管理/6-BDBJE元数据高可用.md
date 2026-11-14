# 元数据一致性
- SR的元数据是最终一致性，间隔比较短1毫秒

# TimeStamp线程
- 如果leader节点长久没有BDBJE日志，那么其它FE怎么感知自己是否和leader节点处于connect状态？
- leader节点有个 TimeStamp线程，每个5秒写入一条BDBJE日志
- 其它非FE节点回放改日志，获取到日志中的时间戳，如果本地时间与该时间戳大于Config.meta_delay_toleration_second的配置时间，那么就认为该FE不再提供服务