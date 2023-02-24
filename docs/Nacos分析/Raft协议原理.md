# Raft协议原理

## 原理动画图：
http://thesecretlivesofdata.com/raft/


## Raft是什么？
Raft协议是一种共识算法，它的目的是保证在一个集群系统中的数据一致性。

## 原理介绍
### 角色
首先定义了集群系统中的节点有三种角色：
- Follower:跟随者
- Candidate：候选者
- Leader：领导者

### 角色之间的转换原理：
开始每个节点都是Follower，当一个Follower在一定时间内接受不到Leader的心跳，就会变为Candidate，向其他节点发起投票请求。
当一个Candidate获得的票数大于等于 2/总节点个数 时，就可以变为Leader。


