#Nacos 集群同步源码分析

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
