#Nacos 集群同步源码分析

## 服务注册数据同步
根据注册的字段ephemeral判断是否是临时客户端还是持久节客户端，不同的节点采用不同的数据同步方式。
临时节点数据同步协议采用的是Distro协议，持久化节点数据同步采用的是Raft协议。

```java 

   // 这个是ServiceManager类的方法，在服务注册时会被调用。
    private void putServiceAndInit(Service service) throws NacosException {
        putService(service);
        service = getService(service.getNamespaceId(), service.getName());
        service.init();
        // 这里根据是否时临时节点添加不同的监听器，用来数据同步
        consistencyService
                .listen(KeyBuilder.buildInstanceListKey(service.getNamespaceId(), service.getName(), true), service);
        consistencyServicebuildInstanceListKey
                .listen(KeyBuilder.(service.getNamespaceId(), service.getName(), false), service);
        Loggers.SRV_LOG.info("[NEW-SERVICE] {}", service.toJson());
    }
    
    // consistencyService
    @Override
    public void listen(String key, RecordListener listener) throws NacosException {
        
        // this special key is listened by both:
        if (KeyBuilder.SERVICE_META_KEY_PREFIX.equals(key)) {
            persistentConsistencyService.listen(key, listener);
            ephemeralConsistencyService.listen(key, listener);
            return;
        }
        // 根据是否是临时节点，选择不同的ConsistencyService
        mapConsistencyService(key).listen(key, listener);
    }
    
    private ConsistencyService mapConsistencyService(String key) {
        return KeyBuilder.matchEphemeralKey(key) ? ephemeralConsistencyService : persistentConsistencyService;
    }
```

- 在服务注册时会调用一个添加实例当service中的方法,在这个方法中
```java 
    public void addInstance(String namespaceId, String serviceName, boolean ephemeral, Instance... ips)
            throws NacosException {
        
        String key = KeyBuilder.buildInstanceListKey(namespaceId, serviceName, ephemeral);
        
        Service service = getService(namespaceId, serviceName);
        
        synchronized (service) {
            List<Instance> instanceList = addIpAddresses(service, ephemeral, ips);
            
            Instances instances = new Instances();
            instances.setInstanceList(instanceList);
            
            // 这里的consistencyService依然是根据注册的客户端是否是临时节点，如果是，则使用DistroConsistencyServiceImpl，
            // 如果不是，则使用RaftConsistencyServiceImpl，后面会详细分析这个方法。
            consistencyService.put(key, instances);
        }
    }
```

### 临时数据节点的数据同步
- 临时节点数据同步采用的是DistroConsistencyServiceImpl这个实现类，在服务注册的时候会添加针对该服务的监听器。
调用的是com.alibaba.nacos.naming.consistency.ephemeral.distro.DistroConsistencyServiceImpl#listen方法 。
```java 
   // 就是添加一个针对该服务的监听器，这里的监听器就是Service。
    @Override
    public void listen(String key, RecordListener listener) throws NacosException {
         // listeners 是一个 Map<String, ConcurrentLinkedQueue<RecordListener>>
        if (!listeners.containsKey(key)) {
            listeners.put(key, new ConcurrentLinkedQueue<>());
        }
        
        if (listeners.get(key).contains(listener)) {
            return;
        }
        // 添加监听器
        listeners.get(key).add(listener);
    }
```
- 在前面ServiceManager的putServiceAndInit方法中，调用了consistencyService.put方法（这里的consistencyService使用的是DistroConsistencyServiceImpl）
consistencyService.put方法中调用了两个方法onPut和distroProtocol.sync，前者做的事是保存实例信息和添加任务，后者用来同步给集群其他节点。
```java 
    @Override
    public void put(String key, Record value) throws NacosException {
        onPut(key, value);
        distroProtocol.sync(new DistroKey(key, KeyBuilder.INSTANCE_LIST_KEY_PREFIX), DataOperation.CHANGE,
                globalConfig.getTaskDispatchPeriod() / 2);
    }
   
```

- DistroConsistencyServiceImpl的onPut方法：
```java 
    // 
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
     // 向阻塞队列中添加了一个任务，服务注册用到类型是DataOperation.CHANGE
     notifier.addTask(key, DataOperation.CHANGE);
    }
```

- DistroConsistencyServiceImpl在启动时，会向线程池中提交一个任务,该任务就是用来处理任务。
```java 
   @PostConstruct
    public void init() {
        GlobalExecutor.submitDistroNotifyTask(notifier);
    }
    
    // 这个notifier要做的事情，
     @Override
     public void run() {
         Loggers.DISTRO.info("distro notifier started");
         
         // 不断轮询从一个阻塞队列中获取任务
         // 该阻塞队列的结构： BlockingQueue<Pair<String, DataOperation>> tasks = new ArrayBlockingQueue<>(1024 * 1024);
         for (; ; ) {
             try {
                 Pair<String, DataOperation> pair = tasks.take();
                 // 开始处理任务
                 handle(pair);
             } catch (Throwable e) {
                 Loggers.DISTRO.error("[NACOS-DISTRO] Error while handling notifying task", e);
             }
         }
     }
     
     // handle(pair)
      private void handle(Pair<String, DataOperation> pair) {
            try {
                String datumKey = pair.getValue0();
                DataOperation action = pair.getValue1();
                
                services.remove(datumKey);
                
                int count = 0;
                
                if (!listeners.containsKey(datumKey)) {
                    return;
                }
                
                for (RecordListener listener : listeners.get(datumKey)) {
                    count++; 
                    try {
                        // 服务注册时条件会成立,调用Service的onChange方法，用来更新Service信息的。
                        if (action == DataOperation.CHANGE) {
                            listener.onChange(datumKey, dataStore.get(datumKey).value);
                            continue;
                        }
                        
                        if (action == DataOperation.DELETE) {
                            listener.onDelete(datumKey);
                            continue;
                        }
                    } catch (Throwable e) {
                        Loggers.DISTRO.error("[NACOS-DISTRO] error while notifying listener of key: {}", datumKey, e);
                    }
                }
                
                if (Loggers.DISTRO.isDebugEnabled()) {
                    Loggers.DISTRO
                            .debug("[NACOS-DISTRO] datum change notified, key: {}, listener count: {}, action: {}",
                                    datumKey, count, action.name());
                }
            } catch (Throwable e) {
                Loggers.DISTRO.error("[NACOS-DISTRO] Error while handling notifying task", e);
            }
        }
```
- 可以看出，上面代码主要是用来更新服务信息的。 而在distroProtocol.sync 同步集群中节点的数据。
```java 
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
    
```
- 其他Nacos节点接受到请求后，是如何处理的？
```java 
    @PutMapping("/datum")
    public ResponseEntity onSyncDatum(@RequestBody Map<String, Datum<Instances>> dataMap) throws Exception {
        
        if (dataMap.isEmpty()) {
            Loggers.DISTRO.error("[onSync] receive empty entity!");
            throw new NacosException(NacosException.INVALID_PARAM, "receive empty entity!");
        }
        
        for (Map.Entry<String, Datum<Instances>> entry : dataMap.entrySet()) {
            if (KeyBuilder.matchEphemeralInstanceListKey(entry.getKey())) {
                String namespaceId = KeyBuilder.getNamespace(entry.getKey());
                String serviceName = KeyBuilder.getServiceName(entry.getKey());
                // 服务不存在，创建服务
                if (!serviceManager.containService(namespaceId, serviceName) && switchDomain
                        .isDefaultInstanceEphemeral()) {
                    serviceManager.createEmptyService(namespaceId, serviceName, true);
                }
                DistroHttpData distroHttpData = new DistroHttpData(createDistroKey(entry.getKey()), entry.getValue());
                // 同步
                distroProtocol.onReceive(distroHttpData);
            }
        }
        return ResponseEntity.ok("ok");
    }
    
    public boolean onReceive(DistroData distroData) {
        String resourceType = distroData.getDistroKey().getResourceType();
        DistroDataProcessor dataProcessor = distroComponentHolder.findDataProcessor(resourceType);
        if (null == dataProcessor) {
            Loggers.DISTRO.warn("[DISTRO] Can't find data process for received data {}", resourceType);
            return false;
        }
        return dataProcessor.processData(distroData);
    }   
    // DistroConsistencyServiceImpl 的processData方法。
    @Override
    public boolean processData(DistroData distroData) {
        DistroHttpData distroHttpData = (DistroHttpData) distroData;
        Datum<Instances> datum = (Datum<Instances>) distroHttpData.getDeserializedContent();
        // 就是调用DistroConsistencyServiceImpl的onPut方法，这个方法前面看过了，就是更新服务。
        onPut(datum.key, datum.value);
        return true;
    }
    
```
### 总结：
- 服务注册时，使用的是DistroConsistencyServiceImpl来实现集群之间数据同步的
- 通过添加一个监听服务变化的监听器，同时，又向阻塞队列中添加一个服务变化的任务，当服务变化了，调用DistroConsistencyServiceImpl的onPut方法来执行更新本地服务信息的操作，实际也调用的是Service的onChange方法。
- 通过添加一个同步数据的任务到阻塞队列中，不断获取任务，发起一个Http Put请求将本机注册的数据同步给其他节点。
- 其他节点收到同步请求后，具体调用的也是DistroConsistencyServiceImpl的onPut方法，实际也会调用Service的onChange方法。



### 持久化节点的数据同步
代码在com.alibaba.nacos.naming.consistency.persistent.raft.RaftCore#signalPublish
如果是该节点不是leader，转发给leader处理，如果是leader，则会向所有节点发起提交请求，提交请求就是要把该数据同步到本地文件中。
```java 
 public void signalPublish(String key, Record value) throws Exception {
        if (stopWork) {
            throw new IllegalStateException("old raft protocol already stop work");
        }
        if (!isLeader()) {
            ObjectNode params = JacksonUtils.createEmptyJsonNode();
            params.put("key", key);
            params.replace("value", JacksonUtils.transferToJsonNode(value));
            Map<String, String> parameters = new HashMap<>(1);
            parameters.put("key", key);
            
            final RaftPeer leader = getLeader();
            // 转发给leaer处理
            raftProxy.proxyPostLarge(leader.ip, API_PUB, params.toString(), parameters);
            return;
        }
        
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
            
            onPublish(datum, peers.local());
            
            final String content = json.toString();
            
            // 等待大多数节点提交成功，才认为是成功，问题：如果有一部分没有成功，那数据就不一致了，怎么处理？
            final CountDownLatch latch = new CountDownLatch(peers.majorityCount());
            for (final String server : peers.allServersIncludeMyself()) {
                if (isLeader(server)) {
                    latch.countDown();
                    continue;
                }
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
```
有一个疑问：如果有一部分节点没有同步成功，那数据就不一致了，怎么处理？

```java 
/**
     * Do publish. If leader, commit publish to store. If not leader, stop publish because should signal to leader.
     *
     * @param datum  datum
     * @param source source raft peer
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
        
        // if data should be persisted, usually this is true:
        if (KeyBuilder.matchPersistentKey(datum.key)) {
            raftStore.write(datum);   // 如果是持久化客户端，则写入磁盘
        }
        
        datums.put(datum.key, datum);
        
        if (isLeader()) {
            local.term.addAndGet(PUBLISH_TERM_INCREASE_COUNT);
        } else {
            if (local.term.get() + PUBLISH_TERM_INCREASE_COUNT > source.term.get()) {
                //set leader term:
                getLeader().term.set(source.term.get());
                local.term.set(getLeader().term.get());
            } else {
                local.term.addAndGet(PUBLISH_TERM_INCREASE_COUNT);
            }
        }
        raftStore.updateTerm(local.term.get());
        NotifyCenter.publishEvent(ValueChangeEvent.builder().key(datum.key).action(DataOperation.CHANGE).build());
        Loggers.RAFT.info("data added/updated, key={}, term={}", datum.key, local.term);
    }
```


### 总结
- 使用了很多的异步任务和队列，Nacos内部会将服务注册的任务放入阻塞队列，采用线程池异步来完成实例更新，从而提高并发写能力。
- 免并发读写的冲突 ，Nacos在更新实例列表时，会采用CopyOnWrite技术，首先将Old实例列表拷贝一份，然后更新拷贝的实例列表，再用更新后的实例列表来覆盖旧的实例列表。
- 临时节点采用Distro协议，持久化节点采用Raft协议处理。



## 配置数据同步


## 集群数据同步源码分析
### Nacos 
- 在DistroProtocol的构造器中开始了全量加载数据的任务。
```java 
    // 构造器
    public DistroProtocol(ServerMemberManager memberManager, DistroComponentHolder distroComponentHolder,
            DistroTaskEngineHolder distroTaskEngineHolder, DistroConfig distroConfig) {
        this.memberManager = memberManager;
        this.distroComponentHolder = distroComponentHolder;
        this.distroTaskEngineHolder = distroTaskEngineHolder;
        this.distroConfig = distroConfig;
        startDistroTask();
    }
    // 
    private void startDistroTask() {
        if (EnvUtil.getStandaloneMode()) {
            isInitialized = true;
            return;
        }
        // 开启数据校验任务
        startVerifyTask();
        // 开启数据加载任务
        startLoadTask();
    }
```

- 先看开启数据加载任务 startLoadTask方法
```java 
     private void startLoadTask() {
        DistroCallback loadCallback = new DistroCallback() {
            @Override
            public void onSuccess() {
                isInitialized = true;
            }
            
            @Override
            public void onFailed(Throwable throwable) {
                isInitialized = false;
            }
        };
        // 提交了一个数据加载的任务
        GlobalExecutor.submitLoadDataTask(
                new DistroLoadDataTask(memberManager, distroComponentHolder, distroConfig, loadCallback));
    }
    
    //DistroLoadDataTask的run方法。
    @Override
    public void run() {
        try {
            // 加载数据
            load();
            // 
            if (!checkCompleted()) {
                GlobalExecutor.submitLoadDataTask(this, distroConfig.getLoadDataRetryDelayMillis());
            } else {
                loadCallback.onSuccess();
                Loggers.DISTRO.info("[DISTRO-INIT] load snapshot data success");
            }
        } catch (Exception e) {
            loadCallback.onFailed(e);
            Loggers.DISTRO.error("[DISTRO-INIT] load snapshot data failed. ", e);
        }
    }
    // 
    private void load() throws Exception {
        // 等待发现集群中其他的节点
        while (memberManager.allMembersWithoutSelf().isEmpty()) {
            Loggers.DISTRO.info("[DISTRO-INIT] waiting server list init...");
            TimeUnit.SECONDS.sleep(1);
        }
        // 等待数据存储类型不为空
        while (distroComponentHolder.getDataStorageTypes().isEmpty()) {
            Loggers.DISTRO.info("[DISTRO-INIT] waiting distro data storage register...");
            TimeUnit.SECONDS.sleep(1);
        }
        for (String each : distroComponentHolder.getDataStorageTypes()) {
            if (!loadCompletedMap.containsKey(each) || !loadCompletedMap.get(each)) {
                // 
                loadCompletedMap.put(each, loadAllDataSnapshotFromRemote(each));
            }
        }
    }
    
    // 
    private boolean loadAllDataSnapshotFromRemote(String resourceType) {
        DistroTransportAgent transportAgent = distroComponentHolder.findTransportAgent(resourceType);
        DistroDataProcessor dataProcessor = distroComponentHolder.findDataProcessor(resourceType);
        if (null == transportAgent || null == dataProcessor) {
            Loggers.DISTRO.warn("[DISTRO-INIT] Can't find component for type {}, transportAgent: {}, dataProcessor: {}",
                    resourceType, transportAgent, dataProcessor);
            return false;
        }
        // 遍历集群中除了自己的其他所有节点
        for (Member each : memberManager.allMembersWithoutSelf()) {
            try {
                Loggers.DISTRO.info("[DISTRO-INIT] load snapshot {} from {}", resourceType, each.getAddress());
                // 获取全量数据
                DistroData distroData = transportAgent.getDatumSnapshot(each.getAddress());
                // 处理全量数据
                boolean result = dataProcessor.processSnapshot(distroData);
                Loggers.DISTRO
                        .info("[DISTRO-INIT] load snapshot {} from {} result: {}", resourceType, each.getAddress(),
                                result);
                if (result) {
                    return true;
                }
            } catch (Exception e) {
                Loggers.DISTRO.error("[DISTRO-INIT] load snapshot {} from {} failed.", resourceType, each.getAddress(), e);
            }
        }
        return false;
    }
    
    // 获取全量数据
    @Override
    public DistroData getDatumSnapshot(String targetServer) {
        try {
            // 通过调用/v1/ns/distro/datums接口获取全部数据
            byte[] allDatum = NamingProxy.getAllData(targetServer);
            return new DistroData(new DistroKey("snapshot", KeyBuilder.INSTANCE_LIST_KEY_PREFIX), allDatum);
        } catch (Exception e) {
            throw new DistroException(String.format("Get snapshot from %s failed.", targetServer), e);
        }
    }
    // 处理全量数据
    @Override
    public boolean processSnapshot(DistroData distroData) {
        try {
            return processData(distroData.getContent());
        } catch (Exception e) {
            return false;
        }
    }
    
    private boolean processData(byte[] data) throws Exception {
        if (data.length > 0) {
            // 先反序列化
            Map<String, Datum<Instances>> datumMap = serializer.deserializeMap(data, Instances.class);
            
            for (Map.Entry<String, Datum<Instances>> entry : datumMap.entrySet()) {
                dataStore.put(entry.getKey(), entry.getValue());
                
                if (!listeners.containsKey(entry.getKey())) {
                    // pretty sure the service not exist:
                    if (switchDomain.isDefaultInstanceEphemeral()) {
                        // create empty service
                        Loggers.DISTRO.info("creating service {}", entry.getKey());
                        Service service = new Service();
                        String serviceName = KeyBuilder.getServiceName(entry.getKey());
                        String namespaceId = KeyBuilder.getNamespace(entry.getKey());
                        service.setName(serviceName);
                        service.setNamespaceId(namespaceId);
                        service.setGroupName(Constants.DEFAULT_GROUP);
                        // now validate the service. if failed, exception will be thrown
                        service.setLastModifiedMillis(System.currentTimeMillis());
                        service.recalculateChecksum();
                        
                        // The Listener corresponding to the key value must not be empty
                        // listeners的结构是： Map<String, ConcurrentLinkedQueue<RecordListener>> listeners
                        // 获取这个key：com.alibaba.nacos.naming.domains.meta.，返回的是队列，然后获取第一个，是一个ServiceManager
                        RecordListener listener = listeners.get(KeyBuilder.SERVICE_META_KEY_PREFIX).peek();
                        if (Objects.isNull(listener)) {
                            return false;
                        }
                        // com.alibaba.nacos.naming.core.ServiceManager#onChange 方法
                        listener.onChange(KeyBuilder.buildServiceMetaKey(namespaceId, serviceName), service);
                    }
                }
            }
            
            for (Map.Entry<String, Datum<Instances>> entry : datumMap.entrySet()) {
                
                if (!listeners.containsKey(entry.getKey())) {
                    // Should not happen:
                    Loggers.DISTRO.warn("listener of {} not found.", entry.getKey());
                    continue;
                }
                
                try {
                    // entry.getKey()返回的结构是： com.alibaba.nacos.naming.iplist.ephemeral.public##DEFAULT_GROUP@@user-service
                    for (RecordListener listener : listeners.get(entry.getKey())) {
                        // listener 是一个Service
                        listener.onChange(entry.getKey(), entry.getValue().value);
                    }
                } catch (Exception e) {
                    Loggers.DISTRO.error("[NACOS-DISTRO] error while execute listener of key: {}", entry.getKey(), e);
                    continue;
                }
                
                // Update data store if listener executed successfully:
                dataStore.put(entry.getKey(), entry.getValue());
            }
        }
        return true;
    } 
```
- 以上可以看出：nacos在启动时会加载其他节点的全量数据，然后在处理数据时调用Service.onChange()和ServiceManager()的onChange()方法。
- 先看ServiceManager的的onChange方法
```java 
 @Override
    public void onChange(String key, Service service) throws Exception {
        try {
            if (service == null) {
                Loggers.SRV_LOG.warn("received empty push from raft, key: {}", key);
                return;
            }
            
            if (StringUtils.isBlank(service.getNamespaceId())) {
                service.setNamespaceId(Constants.DEFAULT_NAMESPACE_ID);
            }
            
            Loggers.RAFT.info("[RAFT-NOTIFIER] datum is changed, key: {}, value: {}", key, service);
            // 先从serviceMap中尝试获取该服务信息
            Service oldDom = getService(service.getNamespaceId(), service.getName());
            
            if (oldDom != null) {
                // 如果已经存在了该服务信息，则更新
                oldDom.update(service);
                // re-listen to handle the situation when the underlying listener is removed:
                consistencyService
                        .listen(KeyBuilder.buildInstanceListKey(service.getNamespaceId(), service.getName(), true),
                                oldDom);
                consistencyService
                        .listen(KeyBuilder.buildInstanceListKey(service.getNamespaceId(), service.getName(), false),
                                oldDom);
            } else {
                // 如果不存在，则加入并初始化
                putServiceAndInit(service);
            }
        } catch (Throwable e) {
            Loggers.SRV_LOG.error("[NACOS-SERVICE] error while processing service update", e);
        }
    }
    
    // putServiceAndInit
    private void putServiceAndInit(Service service) throws NacosException {
        putService(service);
        service = getService(service.getNamespaceId(), service.getName());
        service.init();
        consistencyService
                .listen(KeyBuilder.buildInstanceListKey(service.getNamespaceId(), service.getName(), true), service);
        consistencyService
                .listen(KeyBuilder.buildInstanceListKey(service.getNamespaceId(), service.getName(), false), service);
        Loggers.SRV_LOG.info("[NEW-SERVICE] {}", service.toJson());
    }
    
    /**
     * Put service into manager.
     * 就是将该service放入serviceMap中，使用了双重检锁
     * @param service service
     */
    public void putService(Service service) {
        if (!serviceMap.containsKey(service.getNamespaceId())) {
            synchronized (putServiceLock) {
                if (!serviceMap.containsKey(service.getNamespaceId())) {
                    serviceMap.put(service.getNamespaceId(), new ConcurrentSkipListMap<>());
                }
            }
        }
        serviceMap.get(service.getNamespaceId()).putIfAbsent(service.getName(), service);
    }
    
    // 再看service的init 方法
    /**
     * Init service.
     */
    public void init() {
        // 每隔5秒开始一次nacos客户端的健康检查任务，判定是否是健康状态和是否需要删除。
        HealthCheckReactor.scheduleCheck(clientBeatCheckTask);
        for (Map.Entry<String, Cluster> entry : clusterMap.entrySet()) {
            entry.getValue().setService(this);
            // 这个init方法有空再看。
            entry.getValue().init();
        }
    } 
```
- 全量数据加载任务总结：在nacos服务启动时就会开启使用一个线程去加载其他节点的全量数据，保存起来并开启心跳检查等任务。
- 再看数据校验任务是怎么做的
```java 
    private void startVerifyTask() {
        GlobalExecutor.schedulePartitionDataTimedSync(new DistroVerifyTask(memberManager, distroComponentHolder),
                distroConfig.getVerifyIntervalMillis());
    }
    //DistroVerifyTask的run方法
        @Override
    public void run() {
        try {
            List<Member> targetServer = serverMemberManager.allMembersWithoutSelf();
            if (Loggers.DISTRO.isDebugEnabled()) {
                Loggers.DISTRO.debug("server list is: {}", targetServer);
            }
            for (String each : distroComponentHolder.getDataStorageTypes()) {
                // 校验数据
                verifyForDataStorage(each, targetServer);
            }
        } catch (Exception e) {
            Loggers.DISTRO.error("[DISTRO-FAILED] verify task failed.", e);
        }
    }
    
    // verifyForDataStorage
    private void verifyForDataStorage(String type, List<Member> targetServer) {
    // 这个是服务信息（序列化后的二进制数据）
    DistroData distroData = distroComponentHolder.findDataStorage(type).getVerifyData();
    if (null == distroData) {
        return;
    }
    distroData.setType(DataOperation.VERIFY);
    for (Member member : targetServer) {
        try {
            // 向其他节点开始校验数据
            distroComponentHolder.findTransportAgent(type).syncVerifyData(distroData, member.getAddress());
        } catch (Exception e) {
            Loggers.DISTRO.error(String
                    .format("[DISTRO-FAILED] verify data for type %s to %s failed.", type, member.getAddress()), e);
        }
    }
}
    // syncVerifyData
    @Override
    public boolean syncVerifyData(DistroData verifyData, String targetServer) {
        if (!memberManager.hasMember(targetServer)) {
            return true;
        }
        // 就是向/distro/checksum接口发送Http请求
        NamingProxy.syncCheckSums(verifyData.getContent(), targetServer);
        return true;
    }
```
- /distro/checksum接口做的内容就是和请求数据保持同步，代码就不看了。
- 校验任务总结：校验任务每隔5秒执行一次，首先获取自己节点的数据，然后发送给其他nacos节点，其他nacos节点收到数据后保持和请求过来的数据同步。

### 总结
本文介绍了在nacos服务启动时的两个任务：全量数据加载任务和数据校验任务，前者只会执行一次，后者时一个定时任务。通过这两个任务，
nacos集群中的节点在启动时可以获取全量数据，并且运行时可以将自身的数据同步给其他节点。
