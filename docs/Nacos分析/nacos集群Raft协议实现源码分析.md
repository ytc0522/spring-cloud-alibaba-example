
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
        // 从磁盘加载Datum和term数据进行数据恢复
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
- Leader 发送心跳的逻辑
```java 
        @Override
        public void run() {
            try {
                if (stopWork) {
                    return;
                }
                if (!peers.isReady()) {
                    return;
                }
                
                // local.heartbeatDueMs 初始值默认为5秒，TICK_PERIOD_MS默认为500ms
                // 
                RaftPeer local = peers.local();
                local.heartbeatDueMs -= GlobalExecutor.TICK_PERIOD_MS;
                if (local.heartbeatDueMs > 0) {
                    return;
                }
                // 重置为5秒
                local.resetHeartbeatDue();
                // 发送心跳
                sendBeat();
            } catch (Exception e) {
                Loggers.RAFT.warn("[RAFT] error while sending beat {}", e);
            }
            
        }
        
        // 发送心跳
       private void sendBeat() throws IOException, InterruptedException {
            RaftPeer local = peers.local();
            if (EnvUtil.getStandaloneMode() || local.state != RaftPeer.State.LEADER) {
                return;
            }
            if (Loggers.RAFT.isDebugEnabled()) {
                Loggers.RAFT.debug("[RAFT] send beat with {} keys.", datums.size());
            }
            // 重置投票的时间间隔
            local.resetLeaderDue();
            
            // build data
            // 构建发送的心跳包数据，存放了本机节点和datum数据
            ObjectNode packet = JacksonUtils.createEmptyJsonNode();
            packet.replace("peer", JacksonUtils.transferToJsonNode(local));
            
            ArrayNode array = JacksonUtils.createEmptyArrayNode();
            
            if (switchDomain.isSendBeatOnly()) {
                Loggers.RAFT.info("[SEND-BEAT-ONLY] {}", switchDomain.isSendBeatOnly());
            }
            
            if (!switchDomain.isSendBeatOnly()) {
                for (Datum datum : datums.values()) {
                    
                    ObjectNode element = JacksonUtils.createEmptyJsonNode();
                    
                    if (KeyBuilder.matchServiceMetaKey(datum.key)) {
                        element.put("key", KeyBuilder.briefServiceMetaKey(datum.key));
                    } else if (KeyBuilder.matchInstanceListKey(datum.key)) {
                        element.put("key", KeyBuilder.briefInstanceListkey(datum.key));
                    }
                    element.put("timestamp", datum.timestamp.get());
                    
                    array.add(element);
                }
            }
            
            packet.replace("datums", array);
            // broadcast
            Map<String, String> params = new HashMap<String, String>(1);
            params.put("beat", JacksonUtils.toJson(packet));
            
            String content = JacksonUtils.toJson(params);
            
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            GZIPOutputStream gzip = new GZIPOutputStream(out);
            gzip.write(content.getBytes(StandardCharsets.UTF_8));
            gzip.close();
            
            byte[] compressedBytes = out.toByteArray();
            String compressedContent = new String(compressedBytes, StandardCharsets.UTF_8);
            
            if (Loggers.RAFT.isDebugEnabled()) {
                Loggers.RAFT.debug("raw beat data size: {}, size of compressed data: {}", content.length(),
                        compressedContent.length());
            }
            // 向所有其他的节点发送Http请求，发送心跳包数据
            for (final String server : peers.allServersWithoutMySelf()) {
                try {
                    final String url = buildUrl(server, API_BEAT);
                    if (Loggers.RAFT.isDebugEnabled()) {
                        Loggers.RAFT.debug("send beat to server " + server);
                    }
                    HttpClient.asyncHttpPostLarge(url, null, compressedBytes, new Callback<String>() {
                        @Override
                        public void onReceive(RestResult<String> result) {
                            if (!result.ok()) {
                                Loggers.RAFT.error("NACOS-RAFT beat failed: {}, peer: {}", result.getCode(), server);
                                MetricsMonitor.getLeaderSendBeatFailedException().increment();
                                return;
                            }
                            // 更新到本地的集群节点容器中
                            peers.update(JacksonUtils.toObj(result.getData(), RaftPeer.class));
                            if (Loggers.RAFT.isDebugEnabled()) {
                                Loggers.RAFT.debug("receive beat response from: {}", url);
                            }
                        }
                        
                        @Override
                        public void onError(Throwable throwable) {
                            Loggers.RAFT.error("NACOS-RAFT error while sending heart-beat to peer: {} {}", server,
                                    throwable);
                            MetricsMonitor.getLeaderSendBeatFailedException().increment();
                        }
                        
                        @Override
                        public void onCancel() {
                        
                        }
                    });
                } catch (Exception e) {
                    Loggers.RAFT.error("error while sending heart-beat to peer: {} {}", server, e);
                    MetricsMonitor.getLeaderSendBeatFailedException().increment();
                }
            }
            
        }
    } 
```
- 集群中其他节点收到心跳包后的处理逻辑代码在com.alibaba.nacos.naming.consistency.persistent.raft.RaftCore#receivedBeat中，
逻辑不难，就是比较心跳包中的datum数据是否比较新或者本机是否存在，如果比较新的话，批量对这些数据再获取完整的datum，然后更新。
```java 
public RaftPeer receivedBeat(JsonNode beat) throws Exception {
        if (stopWork) {
            throw new IllegalStateException("old raft protocol already stop work");
        }
        final RaftPeer local = peers.local();
        final RaftPeer remote = new RaftPeer();
        JsonNode peer = beat.get("peer");
        remote.ip = peer.get("ip").asText();
        remote.state = RaftPeer.State.valueOf(peer.get("state").asText());
        remote.term.set(peer.get("term").asLong());
        remote.heartbeatDueMs = peer.get("heartbeatDueMs").asLong();
        remote.leaderDueMs = peer.get("leaderDueMs").asLong();
        remote.voteFor = peer.get("voteFor").asText();
        
        if (remote.state != RaftPeer.State.LEADER) {
            Loggers.RAFT.info("[RAFT] invalid state from master, state: {}, remote peer: {}", remote.state,
                    JacksonUtils.toJson(remote));
            throw new IllegalArgumentException("invalid state from master, state: " + remote.state);
        }
        
        if (local.term.get() > remote.term.get()) {
            Loggers.RAFT
                    .info("[RAFT] out of date beat, beat-from-term: {}, beat-to-term: {}, remote peer: {}, and leaderDueMs: {}",
                            remote.term.get(), local.term.get(), JacksonUtils.toJson(remote), local.leaderDueMs);
            throw new IllegalArgumentException(
                    "out of date beat, beat-from-term: " + remote.term.get() + ", beat-to-term: " + local.term.get());
        }
        
        if (local.state != RaftPeer.State.FOLLOWER) {
            
            Loggers.RAFT.info("[RAFT] make remote as leader, remote peer: {}", JacksonUtils.toJson(remote));
            // mk follower
            local.state = RaftPeer.State.FOLLOWER;
            local.voteFor = remote.ip;
        }
        
        final JsonNode beatDatums = beat.get("datums");
        local.resetLeaderDue();
        local.resetHeartbeatDue();
        
        peers.makeLeader(remote);
        
        if (!switchDomain.isSendBeatOnly()) {
            
            Map<String, Integer> receivedKeysMap = new HashMap<>(datums.size());
            
            for (Map.Entry<String, Datum> entry : datums.entrySet()) {
                receivedKeysMap.put(entry.getKey(), 0);
            }
            
            // now check datums
            List<String> batch = new ArrayList<>();
            
            int processedCount = 0;
            if (Loggers.RAFT.isDebugEnabled()) {
                Loggers.RAFT
                        .debug("[RAFT] received beat with {} keys, RaftCore.datums' size is {}, remote server: {}, term: {}, local term: {}",
                                beatDatums.size(), datums.size(), remote.ip, remote.term, local.term);
            }
            for (Object object : beatDatums) {
                processedCount = processedCount + 1;
                
                JsonNode entry = (JsonNode) object;
                String key = entry.get("key").asText();
                final String datumKey;
                
                if (KeyBuilder.matchServiceMetaKey(key)) {
                    datumKey = KeyBuilder.detailServiceMetaKey(key);
                } else if (KeyBuilder.matchInstanceListKey(key)) {
                    datumKey = KeyBuilder.detailInstanceListkey(key);
                } else {
                    // ignore corrupted key:
                    continue;
                }
                
                long timestamp = entry.get("timestamp").asLong();
                
                receivedKeysMap.put(datumKey, 1);
                
                try {
                    if (datums.containsKey(datumKey) && datums.get(datumKey).timestamp.get() >= timestamp
                            && processedCount < beatDatums.size()) {
                        continue;
                    }
                    // 只有本机不存在的数据或者比较旧的数据才会去更新
                    if (!(datums.containsKey(datumKey) && datums.get(datumKey).timestamp.get() >= timestamp)) {
                        batch.add(datumKey);
                    }
                    // 每到50条数据才开始去更新数据
                    if (batch.size() < 50 && processedCount < beatDatums.size()) {
                        continue;
                    }
                    
                    String keys = StringUtils.join(batch, ",");
                    
                    if (batch.size() <= 0) {
                        continue;
                    }
                    
                    Loggers.RAFT.info("get datums from leader: {}, batch size is {}, processedCount is {}"
                                    + ", datums' size is {}, RaftCore.datums' size is {}", getLeader().ip, batch.size(),
                            processedCount, beatDatums.size(), datums.size());
                    
                    // update datum entry
                    String url = buildUrl(remote.ip, API_GET);
                    Map<String, String> queryParam = new HashMap<>(1);
                    queryParam.put("keys", URLEncoder.encode(keys, "UTF-8"));
                    HttpClient.asyncHttpGet(url, null, queryParam, new Callback<String>() {
                        @Override
                        public void onReceive(RestResult<String> result) {
                            if (!result.ok()) {
                                return;
                            }
                            
                            List<JsonNode> datumList = JacksonUtils
                                    .toObj(result.getData(), new TypeReference<List<JsonNode>>() {
                                    });
                            
                            for (JsonNode datumJson : datumList) {
                                Datum newDatum = null;
                                OPERATE_LOCK.lock();
                                try {
                                    
                                    Datum oldDatum = getDatum(datumJson.get("key").asText());
                                    
                                    if (oldDatum != null && datumJson.get("timestamp").asLong() <= oldDatum.timestamp
                                            .get()) {
                                        Loggers.RAFT
                                                .info("[NACOS-RAFT] timestamp is smaller than that of mine, key: {}, remote: {}, local: {}",
                                                        datumJson.get("key").asText(),
                                                        datumJson.get("timestamp").asLong(), oldDatum.timestamp);
                                        continue;
                                    }
                                    
                                    if (KeyBuilder.matchServiceMetaKey(datumJson.get("key").asText())) {
                                        Datum<Service> serviceDatum = new Datum<>();
                                        serviceDatum.key = datumJson.get("key").asText();
                                        serviceDatum.timestamp.set(datumJson.get("timestamp").asLong());
                                        serviceDatum.value = JacksonUtils
                                                .toObj(datumJson.get("value").toString(), Service.class);
                                        newDatum = serviceDatum;
                                    }
                                    
                                    if (KeyBuilder.matchInstanceListKey(datumJson.get("key").asText())) {
                                        Datum<Instances> instancesDatum = new Datum<>();
                                        instancesDatum.key = datumJson.get("key").asText();
                                        instancesDatum.timestamp.set(datumJson.get("timestamp").asLong());
                                        instancesDatum.value = JacksonUtils
                                                .toObj(datumJson.get("value").toString(), Instances.class);
                                        newDatum = instancesDatum;
                                    }
                                    
                                    if (newDatum == null || newDatum.value == null) {
                                        Loggers.RAFT.error("receive null datum: {}", datumJson);
                                        continue;
                                    }
                                    
                                    raftStore.write(newDatum);
                                    
                                    datums.put(newDatum.key, newDatum);
                                    notifier.notify(newDatum.key, DataOperation.CHANGE, newDatum.value);
                                    
                                    local.resetLeaderDue();
                                    
                                    if (local.term.get() + 100 > remote.term.get()) {
                                        getLeader().term.set(remote.term.get());
                                        local.term.set(getLeader().term.get());
                                    } else {
                                        local.term.addAndGet(100);
                                    }
                                    
                                    raftStore.updateTerm(local.term.get());
                                    
                                    Loggers.RAFT.info("data updated, key: {}, timestamp: {}, from {}, local term: {}",
                                            newDatum.key, newDatum.timestamp, JacksonUtils.toJson(remote), local.term);
                                    
                                } catch (Throwable e) {
                                    Loggers.RAFT
                                            .error("[RAFT-BEAT] failed to sync datum from leader, datum: {}", newDatum,
                                                    e);
                                } finally {
                                    OPERATE_LOCK.unlock();
                                }
                            }
                            try {
                                TimeUnit.MILLISECONDS.sleep(200);
                            } catch (InterruptedException e) {
                                Loggers.RAFT.error("[RAFT-BEAT] Interrupted error ", e);
                            }
                            return;
                        }
                        
                        @Override
                        public void onError(Throwable throwable) {
                            Loggers.RAFT.error("[RAFT-BEAT] failed to sync datum from leader", throwable);
                        }
                        
                        @Override
                        public void onCancel() {
                        
                        }
                        
                    });
                    
                    batch.clear();
                    
                } catch (Exception e) {
                    Loggers.RAFT.error("[NACOS-RAFT] failed to handle beat entry, key: {}", datumKey);
                }
                
            }
            
            List<String> deadKeys = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : receivedKeysMap.entrySet()) {
                if (entry.getValue() == 0) {
                    deadKeys.add(entry.getKey());
                }
            }
            
            for (String deadKey : deadKeys) {
                try {
                    deleteDatum(deadKey);
                } catch (Exception e) {
                    Loggers.RAFT.error("[NACOS-RAFT] failed to remove entry, key={} {}", deadKey, e);
                }
            }
            
        }
        
        return local;
    }
```


