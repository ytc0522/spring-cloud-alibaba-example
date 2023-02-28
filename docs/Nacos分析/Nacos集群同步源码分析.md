# Nacos 集群同步源码分析

## 版本介绍
- Nacos服务端版本是1.4.2。

## 分析思路
- 临时节点数据同步方式
- 持久化节点的数据同步方式

## 服务注册的数据同步
根据注册的字段ephemeral判断是否是临时客户端还是持久节客户端，不同的节点采用不同的数据同步方式。
临时节点数据同步协议采用的是Distro协议，持久化节点数据同步采用的是Raft协议。
在服务注册时会调用ServiceManager.addInstance方法。
```java 
    public void addInstance(String namespaceId, String serviceName, boolean ephemeral, Instance... ips)
            throws NacosException {
        
        String key = KeyBuilder.buildInstanceListKey(namespaceId, serviceName, ephemeral);
        
        Service service = getService(namespaceId, serviceName);
        
        synchronized (service) {
            List<Instance> instanceList = addIpAddresses(service, ephemeral, ips);
            
            Instances instances = new Instances();
            instances.setInstanceList(instanceList);
            
            // 这里的consistencyService根据注册的客户端是否是临时节点，
            // 如果是，则使用DistroConsistencyServiceImpl
            // 如果不是，则使用RaftConsistencyServiceImpl
            // 这样，不同的节点类型使用不同的同步方式
            consistencyService.put(key, instances);
        }
    }
```

### 临时数据节点的数据同步
DistroConsistencyServiceImpl
```java
@DependsOn("ProtocolManager")
@org.springframework.stereotype.Service("distroConsistencyService")
public class DistroConsistencyServiceImpl implements EphemeralConsistencyService, DistroDataProcessor {
    @PostConstruct
    public void init() {
        GlobalExecutor.submitDistroNotifyTask(notifier);
    }

    // 从这里开始进行数据同步
    @Override
    public void put(String key, Record value) throws NacosException {
        // 用于更新本机的服务列表
        onPut(key, value);
        // 集群同步
        distroProtocol.sync(new DistroKey(key, KeyBuilder.INSTANCE_LIST_KEY_PREFIX), DataOperation.CHANGE,
                globalConfig.getTaskDispatchPeriod() / 2);
    }


    public void onPut(String key, Record value) {
        if (KeyBuilder.matchEphemeralInstanceListKey(key)) {
            Datum<Instances> datum = new Datum<>();
            datum.value = (Instances) value;
            datum.key = key;
            datum.timestamp.incrementAndGet();
            // 向datastore中添加
            dataStore.put(key, datum);
        }

        if (!listeners.containsKey(key)) {
            return;
        }
        // 向阻塞队列中添加了一个任务，通过一个单线程的线程池不断轮询获取任务，判断任务的类型DataOperation.CHANGE或者DataOperation.DELETE
        // 这块和同步关系不大，不用关注
        notifier.addTask(key, DataOperation.CHANGE);
    }
}
```

DistroProtocol
```java
@Component
public class DistroProtocol {
    /**
     * Start to sync data to all remote server.
     *
     * @param distroKey distro key of sync data
     * @param action    the action of data operation
     */
    public void sync(DistroKey distroKey, DataOperation action, long delay) {
        for (Member each : memberManager.allMembersWithoutSelf()) {
            DistroKey distroKeyWithTarget = new DistroKey(distroKey.getResourceKey(), distroKey.getResourceType(),
                    each.getAddress());
            // 注意，这里就是添加了一个同步给集群其他节点的任务
            DistroDelayTask distroDelayTask = new DistroDelayTask(distroKeyWithTarget, action, delay);
            distroTaskEngineHolder.getDelayTaskExecuteEngine().addTask(distroKeyWithTarget, distroDelayTask);
            if (Loggers.DISTRO.isDebugEnabled()) {
                Loggers.DISTRO.debug("[DISTRO-SCHEDULE] {} to {}", distroKey, each.getAddress());
            }
        }
    }
    
    
    // 具体执行任务的代码在com.alibaba.nacos.core.distributed.distro.task.execute.DistroSyncChangeTask#run
        @Override
    public void run() {
        Loggers.DISTRO.info("[DISTRO-START] {}", toString());
        try {
            String type = getDistroKey().getResourceType();
            DistroData distroData = distroComponentHolder.findDataStorage(type).getDistroData(getDistroKey());
            distroData.setType(DataOperation.CHANGE);
            
            // 开始同步服务数据了
            boolean result = distroComponentHolder.findTransportAgent(type).syncData(distroData, getDistroKey().getTargetServer());
            if (!result) {
                // 同步失败的话，会重试处理。属于AP模式。
                handleFailedTask();
            }
            Loggers.DISTRO.info("[DISTRO-END] {} result: {}", toString(), result);
        } catch (Exception e) {
            Loggers.DISTRO.warn("[DISTRO] Sync data change failed.", e);
            handleFailedTask();
        }
    }
    
    @Override
    public boolean syncData(DistroData data, String targetServer) {
        if (!memberManager.hasMember(targetServer)) {
            return true;
        }
        byte[] dataContent = data.getContent();
        
        // 底层使用的是NamingProxy来做
        return NamingProxy.syncData(dataContent, data.getDistroKey().getTargetServer());
    }
    // NameProxy的syncData
    public static boolean syncData(byte[] data, String curServer) {
        Map<String, String> headers = new HashMap<>(128);
        
        headers.put(HttpHeaderConsts.CLIENT_VERSION_HEADER, VersionUtils.version);
        headers.put(HttpHeaderConsts.USER_AGENT_HEADER, UtilsAndCommons.SERVER_VERSION);
        headers.put(HttpHeaderConsts.ACCEPT_ENCODING, "gzip,deflate,sdch");
        headers.put(HttpHeaderConsts.CONNECTION, "Keep-Alive");
        headers.put(HttpHeaderConsts.CONTENT_ENCODING, "gzip");
        
        try {
            // 通过Http的形式给集群中其他节点同步数据
            RestResult<String> result = HttpClient.httpPutLarge(
                    // 接口地址 v1/ns/distro/datum
                    "http://" + curServer + EnvUtil.getContextPath() + UtilsAndCommons.NACOS_NAMING_CONTEXT
                            + DATA_ON_SYNC_URL, headers, data);
            if (result.ok()) {
                return true;
            }
            if (HttpURLConnection.HTTP_NOT_MODIFIED == result.getCode()) {
                return true;
            }
            throw new IOException("failed to req API:" + "http://" + curServer + EnvUtil.getContextPath()
                    + UtilsAndCommons.NACOS_NAMING_CONTEXT + DATA_ON_SYNC_URL + ". code:" + result.getCode() + " msg: "
                    + result.getData());
        } catch (Exception e) {
            Loggers.SRV_LOG.warn("NamingProxy", e);
        }
        return false;
    }
}
```
其他Nacos节点接受到请求后，调用DistroConsistencyServiceImpl的onPut方法完成本机服务的更新，代码不难，就不看了。

### 持久化节点的数据同步
**RaftConsistencyServiceImpl**

```java
@Deprecated
@DependsOn("ProtocolManager")
@Service
public class RaftConsistencyServiceImpl implements PersistentConsistencyService {
    
    
    
    @Override
    public void put(String key, Record value) throws NacosException {
        checkIsStopWork();
        try {
            // 底层使用的是RaftCore
            raftCore.signalPublish(key, value);
        } catch (Exception e) {
            Loggers.RAFT.error("Raft put failed.", e);
            throw new NacosException(NacosException.SERVER_ERROR, "Raft put failed, key:" + key + ", value:" + value,
                    e);
        }
    }
}
```

**RaftCore**
```java
@Deprecated
@DependsOn("ProtocolManager")
@Component
public class RaftCore implements Closeable {
    public void signalPublish(String key, Record value) throws Exception {
        if (stopWork) {
            throw new IllegalStateException("old raft protocol already stop work");
        }
        // 先判断是否是Leader
        if (!isLeader()) {
            ObjectNode params = JacksonUtils.createEmptyJsonNode();
            params.put("key", key);
            params.replace("value", JacksonUtils.transferToJsonNode(value));
            Map<String, String> parameters = new HashMap<>(1);
            parameters.put("key", key);

            final RaftPeer leader = getLeader();
            // 转发给Leader处理
            raftProxy.proxyPostLarge(leader.ip, API_PUB, params.toString(), parameters);
            return;
        }
        // ReentrantLock
        OPERATE_LOCK.lock();
        try {
            final long start = System.currentTimeMillis();
            final Datum datum = new Datum();
            datum.key = key;
            datum.value = value;
            if (getDatum(key) == null) {
                datum.timestamp.set(1L);
            } else {
                datum.timestamp.set(getDatum(key).timestamp.incrementAndGet());
            }

            ObjectNode json = JacksonUtils.createEmptyJsonNode();
            json.replace("datum", JacksonUtils.transferToJsonNode(datum));
            json.replace("source", JacksonUtils.transferToJsonNode(peers.local()));
            
            // 这是一个重要的方法，在同步时要保存数据，其他节点在接受到同步请求后，也要调用该方法。
            onPublish(datum, peers.local());

            final String content = json.toString();

            // 等待大多数节点提交成功，才认为是成功，问题：如果有一部分没有成功，那数据就不一致了，怎么处理？
            final CountDownLatch latch = new CountDownLatch(peers.majorityCount());
            // 向集群中所有节点发送同步请求
            for (final String server : peers.allServersIncludeMyself()) {
                if (isLeader(server)) {
                    latch.countDown();
                    continue;
                }
                // 
                final String url = buildUrl(server, API_ON_PUB);
                HttpClient.asyncHttpPostLarge(url, Arrays.asList("key", key), content, new Callback<String>() {
                    @Override
                    public void onReceive(RestResult<String> result) {
                        if (!result.ok()) {
                            Loggers.RAFT
                                    .warn("[RAFT] failed to publish data to peer, datumId={}, peer={}, http code={}",
                                            datum.key, server, result.getCode());
                            return;
                        }
                        latch.countDown();
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        Loggers.RAFT.error("[RAFT] failed to publish data to peer", throwable);
                    }

                    @Override
                    public void onCancel() {

                    }
                });
            }

            if (!latch.await(UtilsAndCommons.RAFT_PUBLISH_TIMEOUT, TimeUnit.MILLISECONDS)) {
                // only majority servers return success can we consider this update success
                Loggers.RAFT.error("data publish failed, caused failed to notify majority, key={}", key);
                throw new IllegalStateException("data publish failed, caused failed to notify majority, key=" + key);
            }

            long end = System.currentTimeMillis();
            Loggers.RAFT.info("signalPublish cost {} ms, key: {}", (end - start), key);
        } finally {
            OPERATE_LOCK.unlock();
        }
    }

    /**
     * Do publish. If leader, commit publish to store. If not leader, stop publish because should signal to leader.
     *
     * @param datum  datum              要同步的数据
     * @param source source raft peer   发起同步请求的节点
     * @throws Exception any exception during publish
     */
    public void onPublish(Datum datum, RaftPeer source) throws Exception {
        if (stopWork) {
            throw new IllegalStateException("old raft protocol already stop work");
        }
        RaftPeer local = peers.local();
        if (datum.value == null) {
            Loggers.RAFT.warn("received empty datum");
            throw new IllegalStateException("received empty datum");
        }

        if (!peers.isLeader(source.ip)) {
            Loggers.RAFT
                    .warn("peer {} tried to publish data but wasn't leader, leader: {}", JacksonUtils.toJson(source),
                            JacksonUtils.toJson(getLeader()));
            throw new IllegalStateException("peer(" + source.ip + ") tried to publish " + "data but wasn't leader");
        }

        if (source.term.get() < local.term.get()) {
            Loggers.RAFT.warn("out of date publish, pub-term: {}, cur-term: {}", JacksonUtils.toJson(source),
                    JacksonUtils.toJson(local));
            throw new IllegalStateException(
                    "out of date publish, pub-term:" + source.term.get() + ", cur-term: " + local.term.get());
        }

        local.resetLeaderDue();

        // 写入磁盘
        // if data should be persisted, usually this is true:
        if (KeyBuilder.matchPersistentKey(datum.key)) {
            raftStore.write(datum);   
        }
        // 还要写入内存，这是一个Map
        datums.put(datum.key, datum);

        if (isLeader()) {
            // 任期增加100
            local.term.addAndGet(PUBLISH_TERM_INCREASE_COUNT);
        } else {
            if (local.term.get() + PUBLISH_TERM_INCREASE_COUNT > source.term.get()) {
                //set leader term:
                // 更新Leader的任期为其集群中最大的term
                getLeader().term.set(source.term.get());
                local.term.set(getLeader().term.get());
            } else {
                local.term.addAndGet(PUBLISH_TERM_INCREASE_COUNT);
            }
        }
        // 将Term写入磁盘
        raftStore.updateTerm(local.term.get());
        // 发布事件改变事件
        NotifyCenter.publishEvent(ValueChangeEvent.builder().key(datum.key).action(DataOperation.CHANGE).build());
        Loggers.RAFT.info("data added/updated, key={}, term={}", datum.key, local.term);
    }
}
```

## 总结
#### 临时服务的数据同步
- 服务注册时，使用的是DistroProtocol来实现集群之间数据同步的
- 通过添加一个同步数据的任务到阻塞队列中，不断获取任务，发起一个Http Put请求将本机注册的数据同步给其他节点。
- 其他节点收到同步请求后，具体调用的也是DistroConsistencyServiceImpl的onPut方法，实际也会调用Service的onChange方法。

#### 持久化服务的数据同步
- 服务注册时，使用的时RaftCore来实现集群之间数据同步的。
- 集群中的Leader首先将更新的数据、增加任期、任期写入磁盘，然后给所有节点发起提交请求，其他节点同步更新。

