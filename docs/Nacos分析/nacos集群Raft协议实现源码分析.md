
#Nacos集群Raft协议源码分析

### 源码分析过程
- RaftCore是Raft初始化的地方，先从这里看起
- 先看构造器
```java 
    public RaftCore(RaftPeerSet peers, SwitchDomain switchDomain, GlobalConfig globalConfig, RaftProxy raftProxy,
            RaftStore raftStore, ClusterVersionJudgement versionJudgement, RaftListener raftListener) {
        this.peers = peers;
        this.switchDomain = switchDomain;
        this.globalConfig = globalConfig;
        this.raftProxy = raftProxy;
        this.raftStore = raftStore;
        this.versionJudgement = versionJudgement;
        this.notifier = new PersistentNotifier(key -> null == getDatum(key) ? null : getDatum(key).value);
        this.publisher = NotifyCenter.registerToPublisher(ValueChangeEvent.class, 16384);
        this.raftListener = raftListener;
    }
```
- 再看是如何启动的
```java 
    @PostConstruct
    public void init() throws Exception {
        Loggers.RAFT.info("initializing Raft sub-system");
        final long start = System.currentTimeMillis();
        
        raftStore.loadDatums(notifier, datums);
        // 任期初始化，不指定就默认为0
        setTerm(NumberUtils.toLong(raftStore.loadMeta().getProperty("term"), 0L));
        
        Loggers.RAFT.info("cache loaded, datum count: {}, current term: {}", datums.size(), peers.getTerm());
        
        initialized = true;
        
        Loggers.RAFT.info("finish to load data from disk, cost: {} ms.", (System.currentTimeMillis() - start));
        
        // 注册一个主节点选举的任务，每隔500毫秒执行
        masterTask = GlobalExecutor.registerMasterElection(new MasterElection());
        
        // 注册一个向主节点发送心跳的任务，每隔500毫秒执行一次
        heartbeatTask = GlobalExecutor.registerHeartbeat(new HeartBeat());
        
        versionJudgement.registerObserver(isAllNewVersion -> {
            stopWork = isAllNewVersion;
            if (stopWork) {
                try {
                    shutdown();
                    raftListener.removeOldRaftMetadata();
                } catch (NacosException e) {
                    throw new NacosRuntimeException(NacosException.SERVER_ERROR, e);
                }
            }
        }, 100);
        
        NotifyCenter.registerSubscriber(notifier);
        
        Loggers.RAFT.info("timer started: leader timeout ms: {}, heart-beat timeout ms: {}",
                GlobalExecutor.LEADER_TIMEOUT_MS, GlobalExecutor.HEARTBEAT_INTERVAL_MS);
    }
```
- 选举的逻辑代码
```java
public class MasterElection implements Runnable {

    @Override
    public void run() {
        try {
            if (stopWork) {
                return;
            }
            if (!peers.isReady()) {
                return;
            }
            
            RaftPeer local = peers.local();
            // local.leaderDueMs的初始值是0-15秒之间的随机数，每次减去500ms
            local.leaderDueMs -= GlobalExecutor.TICK_PERIOD_MS;
            // 至于当local.leaderDueMs小于零时，才会进行投票
            if (local.leaderDueMs > 0) {
                return;
            }

            // reset timeout
            // 重新设置超时时间
            local.resetLeaderDue();
            local.resetHeartbeatDue();
            // 发起投票
            sendVote();
        } catch (Exception e) {
            Loggers.RAFT.warn("[RAFT] error while master election {}", e);
        }

    }
}
```
- 看下是如何进行投票的

```java 
private void sendVote() {
            // 只有当leader超时了，才会开始投票
            RaftPeer local = peers.get(NetUtils.localServer());
            Loggers.RAFT.info("leader timeout, start voting,leader: {}, term: {}", JacksonUtils.toJson(getLeader()),
                    local.term);
            
            // 重置集群节点的投票信息和leader信息
            peers.reset();
            // 任期加一
            local.term.incrementAndGet();
            // 当前节点投票给自己
            local.voteFor = local.ip;
            // 修改当前的角色为候选者
            local.state = RaftPeer.State.CANDIDATE;
            // 构建投票请求参数，将自己节点发送过去
            Map<String, String> params = new HashMap<>(1);
            params.put("vote", JacksonUtils.toJson(local));
            
            // 向集群中其他节点发送投票请求
            for (final String server : peers.allServersWithoutMySelf()) {
                final String url = buildUrl(server, API_VOTE);
                try {
                    HttpClient.asyncHttpPost(url, null, params, new Callback<String>() {
                        @Override
                        public void onReceive(RestResult<String> result) {
                            if (!result.ok()) {
                                Loggers.RAFT.error("NACOS-RAFT vote failed: {}, url: {}", result.getCode(), url);
                                return;
                            }
                            // 
                            RaftPeer peer = JacksonUtils.toObj(result.getData(), RaftPeer.class);
                            
                            Loggers.RAFT.info("received approve from peer: {}", JacksonUtils.toJson(peer));
                            // 决定谁是leader
                            peers.decideLeader(peer);
                            
                        }
                        
                        @Override
                        public void onError(Throwable throwable) {
                            Loggers.RAFT.error("error while sending vote to server: {}", server, throwable);
                        }
                        
                        @Override
                        public void onCancel() {
                        
                        }
                    });
                } catch (Exception e) {
                    Loggers.RAFT.warn("error while sending vote to server: {}", server);
                }
            }
        }
    }
```
- 选出leader的逻辑：
```java 
    public RaftPeer decideLeader(RaftPeer candidate) {
        peers.put(candidate.ip, candidate);
        // 被投票的Ip容器
        SortedBag ips = new TreeBag();
        // 集群中获取票数最多的值
        int maxApproveCount = 0;
        // 集群中获取票数最多的节点
        String maxApprovePeer = null;
        // 遍历所有节点，得到获取最多票数的节点和最多票数值
        for (RaftPeer peer : peers.values()) {
            if (StringUtils.isEmpty(peer.voteFor)) {
                continue;
            }
            // 
            ips.add(peer.voteFor);
            if (ips.getCount(peer.voteFor) > maxApproveCount) {
                maxApproveCount = ips.getCount(peer.voteFor);
                maxApprovePeer = peer.voteFor;
            }
        }
        // 判断是否大于大多数节点个数
        if (maxApproveCount >= majorityCount()) {
            // 设置Leaer
            RaftPeer peer = peers.get(maxApprovePeer);
            peer.state = RaftPeer.State.LEADER;
            
            if (!Objects.equals(leader, peer)) {
                leader = peer;
                // 当leader有变化，发布leader选举结束的事件
                ApplicationUtils.publishEvent(new LeaderElectFinishedEvent(this, leader, local()));
                Loggers.RAFT.info("{} has become the LEADER", leader.ip);
            }
        }
        return leader;
    }
```
- 收到投票请求的节点的处理的逻辑
```java 
     @PostMapping("/vote")
    public JsonNode vote(HttpServletRequest request, HttpServletResponse response) throws Exception {
        if (versionJudgement.allMemberIsNewVersion()) {
        throw new IllegalStateException("old raft protocol already stop");
        }
        RaftPeer peer = raftCore.receivedVote(JacksonUtils.toObj(WebUtils.required(request, "vote"), RaftPeer.class));

        return JacksonUtils.transferToJsonNode(peer);
        }
        
            public synchronized RaftPeer receivedVote(RaftPeer remote) {
        if (stopWork) {
            throw new IllegalStateException("old raft protocol already stop work");
        }
        if (!peers.contains(remote)) {
            throw new IllegalStateException("can not find peer: " + remote.ip);
        }
        
        RaftPeer local = peers.get(NetUtils.localServer());
        if (remote.term.get() <= local.term.get()) {
            String msg = "received illegitimate vote" + ", voter-term:" + remote.term + ", votee-term:" + local.term;
            
            Loggers.RAFT.info(msg);
            if (StringUtils.isEmpty(local.voteFor)) {
                local.voteFor = local.ip;
            }
            
            return local;
        }
        
        local.resetLeaderDue();
        
        local.state = RaftPeer.State.FOLLOWER;
        local.voteFor = remote.ip;
        local.term.set(remote.term.get());
        
        Loggers.RAFT.info("vote {} as leader, term: {}", remote.ip, remote.term);
        
        return local;
    }
    
    public synchronized RaftPeer receivedVote(RaftPeer remote) {
        if (stopWork) {
            throw new IllegalStateException("old raft protocol already stop work");
        }
        if (!peers.contains(remote)) {
            throw new IllegalStateException("can not find peer: " + remote.ip);
        }
        
        // 如果当前任期大于参数节点的任期，则投票给自己
        RaftPeer local = peers.get(NetUtils.localServer());
        if (remote.term.get() <= local.term.get()) {
            String msg = "received illegitimate vote" + ", voter-term:" + remote.term + ", votee-term:" + local.term;
            
            Loggers.RAFT.info(msg);
            if (StringUtils.isEmpty(local.voteFor)) {
                local.voteFor = local.ip;
            }
            
            return local;
        }
        
        // 将自己角色设置为跟随者。
        local.resetLeaderDue();
        
        local.state = RaftPeer.State.FOLLOWER;
        local.voteFor = remote.ip;
        local.term.set(remote.term.get());
        
        Loggers.RAFT.info("vote {} as leader, term: {}", remote.ip, remote.term);
        
        return local;
    }
```

- 

