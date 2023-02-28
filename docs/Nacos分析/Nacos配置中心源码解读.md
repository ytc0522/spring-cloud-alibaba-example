# Nacos 配置中心源码分析

## 版本介绍
Nacos客户端和服务端版本都是1.4.2。
spring-cloud-alibaba-starters版本2.2.6.RELEASE。

## 分析思路
- Nacos客户端主动获取配置
- Nacos客户端和服务端如何保持同步更新的

### Nacos客户端主动获取配置

- 首先到找自动配置入口类，查看spring-cloud-starter-alibaba-nacos-config包下的META-INF目录下spring.factories
```properties
org.springframework.cloud.bootstrap.BootstrapConfiguration=\
com.alibaba.cloud.nacos.NacosConfigBootstrapConfiguration
org.springframework.boot.autoconfigure.EnableAutoConfiguration=\
com.alibaba.cloud.nacos.NacosConfigAutoConfiguration,\
com.alibaba.cloud.nacos.endpoint.NacosConfigEndpointAutoConfiguration
org.springframework.boot.diagnostics.FailureAnalyzer=\
com.alibaba.cloud.nacos.diagnostics.analyzer.NacosConnectionFailureAnalyzer
org.springframework.boot.env.PropertySourceLoader=\
com.alibaba.cloud.nacos.parser.NacosJsonPropertySourceLoader,\
com.alibaba.cloud.nacos.parser.NacosXmlPropertySourceLoader
org.springframework.context.ApplicationListener=\
com.alibaba.cloud.nacos.logging.NacosLoggingListener
```
很重要的两个自动配置类：
- NacosConfigBootstrapConfiguration：主动获取Nacos服务端的配置
- NacosConfigAutoConfiguration：


看下如何加载配置的
NacosConfigBootstrapConfiguration
```java
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "spring.cloud.nacos.config.enabled", matchIfMissing = true)
public class NacosConfigBootstrapConfiguration {

	@Bean
	@ConditionalOnMissingBean
	public NacosConfigProperties nacosConfigProperties() {
		return new NacosConfigProperties();
	}

	@Bean
	@ConditionalOnMissingBean
	public NacosConfigManager nacosConfigManager(
			NacosConfigProperties nacosConfigProperties) {
		return new NacosConfigManager(nacosConfigProperties);
	}

	@Bean
	public NacosPropertySourceLocator nacosPropertySourceLocator(
			NacosConfigManager nacosConfigManager) {
		return new NacosPropertySourceLocator(nacosConfigManager);
	}
}
```

NacosPropertySourceLocator 实现了Spring Cloud的 PropertySourceLocator，locate方法会在项目初始的时候加载，
用于获取加载存放在Nacos配置中心的配置信息。

```java
@Order(0)
public class NacosPropertySourceLocator implements PropertySourceLocator {

   private static final Logger log = LoggerFactory
           .getLogger(NacosPropertySourceLocator.class);

   private static final String NACOS_PROPERTY_SOURCE_NAME = "NACOS";

   private static final String SEP1 = "-";

   private static final String DOT = ".";

   private NacosPropertySourceBuilder nacosPropertySourceBuilder;

   private NacosConfigProperties nacosConfigProperties;

   private NacosConfigManager nacosConfigManager;

   /**
    * recommend to use
    * {@link NacosPropertySourceLocator#NacosPropertySourceLocator(com.alibaba.cloud.nacos.NacosConfigManager)}.
    * @param nacosConfigProperties nacosConfigProperties
    */
   @Deprecated
   public NacosPropertySourceLocator(NacosConfigProperties nacosConfigProperties) {
      this.nacosConfigProperties = nacosConfigProperties;
   }

   public NacosPropertySourceLocator(NacosConfigManager nacosConfigManager) {
      this.nacosConfigManager = nacosConfigManager;
      this.nacosConfigProperties = nacosConfigManager.getNacosConfigProperties();
   }

   @Override
   public PropertySource<?> locate(Environment env) {
      nacosConfigProperties.setEnvironment(env);
      ConfigService configService = nacosConfigManager.getConfigService();

      if (null == configService) {
         log.warn("no instance of config service found, can't load config from nacos");
         return null;
      }
      long timeout = nacosConfigProperties.getTimeout();
      nacosPropertySourceBuilder = new NacosPropertySourceBuilder(configService,
              timeout);
      String name = nacosConfigProperties.getName();

      String dataIdPrefix = nacosConfigProperties.getPrefix();
      if (StringUtils.isEmpty(dataIdPrefix)) {
         dataIdPrefix = name;
      }

      if (StringUtils.isEmpty(dataIdPrefix)) {
         dataIdPrefix = env.getProperty("spring.application.name");
      }

      CompositePropertySource composite = new CompositePropertySource(
              NACOS_PROPERTY_SOURCE_NAME);
      // 加载配置
      // 加载这个参数指定的配置 spring.cloud.nacos.config.shared-configs[0]=xxx 
      loadSharedConfiguration(composite);
      //加载这个参数指定的配置 spring.cloud.nacos.config.extension-configs[0]=xxx 
      loadExtConfiguration(composite);
      // 加载 默认的nacos配置中心里的配置
      loadApplicationConfiguration(composite, dataIdPrefix, nacosConfigProperties, env);
      return composite;
   }


   /**
    * load configuration of application.
    */
   private void loadApplicationConfiguration(
           CompositePropertySource compositePropertySource, String dataIdPrefix,
           NacosConfigProperties properties, Environment environment) {
      String fileExtension = properties.getFileExtension();
      String nacosGroup = properties.getGroup();
      
      // 下面几个loadNacosDataIfPresent方法，是加载不同格式的数据
      // load directly once by default 这个加载没有后缀的
      loadNacosDataIfPresent(compositePropertySource, dataIdPrefix, nacosGroup,
              fileExtension, true);
      // load with suffix, which have a higher priority than the default
      // 这个是加载带文件后缀的
      loadNacosDataIfPresent(compositePropertySource,
              dataIdPrefix + DOT + fileExtension, nacosGroup, fileExtension, true);
      // Loaded with profile, which have a higher priority than the suffix
      // 加载带环境的配置
      for (String profile : environment.getActiveProfiles()) {
         String dataId = dataIdPrefix + SEP1 + profile + DOT + fileExtension;
         loadNacosDataIfPresent(compositePropertySource, dataId, nacosGroup,
                 fileExtension, true);
      }
   }
}
```
加载配置最终会调用NacosConfigService
```java
public class NacosConfigService implements ConfigService {
    
   private String getConfigInner(String tenant, String dataId, String group, long timeoutMs) throws NacosException {
      group = blank2defaultGroup(group);
      ParamUtils.checkKeyParam(dataId, group);
      ConfigResponse cr = new ConfigResponse();

      cr.setDataId(dataId);
      cr.setTenant(tenant);
      cr.setGroup(group);

      // 优先使用本地配置
      String content = LocalConfigInfoProcessor.getFailover(agent.getName(), dataId, group, tenant);
      if (content != null) {
         LOGGER.warn("[{}] [get-config] get failover ok, dataId={}, group={}, tenant={}, config={}", agent.getName(),
                 dataId, group, tenant, ContentUtils.truncateContent(content));
         cr.setContent(content);
         String encryptedDataKey = LocalEncryptedDataKeyProcessor
                 .getEncryptDataKeyFailover(agent.getName(), dataId, group, tenant);
         cr.setEncryptedDataKey(encryptedDataKey);
         configFilterChainManager.doFilter(null, cr);
         content = cr.getContent();
         return content;
      }

      try {
          // 这里是到Nacos服务端获取指定的配置文件，代码就不看了
         ConfigResponse response = worker.getServerConfig(dataId, group, tenant, timeoutMs);
         cr.setContent(response.getContent());
         cr.setEncryptedDataKey(response.getEncryptedDataKey());

         configFilterChainManager.doFilter(null, cr);
         content = cr.getContent();

         return content;
      } catch (NacosException ioe) {
         if (NacosException.NO_RIGHT == ioe.getErrCode()) {
            throw ioe;
         }
         LOGGER.warn("[{}] [get-config] get from server error, dataId={}, group={}, tenant={}, msg={}",
                 agent.getName(), dataId, group, tenant, ioe.toString());
      }

      LOGGER.warn("[{}] [get-config] get snapshot ok, dataId={}, group={}, tenant={}, config={}", agent.getName(),
              dataId, group, tenant, ContentUtils.truncateContent(content));
      content = LocalConfigInfoProcessor.getSnapshot(agent.getName(), dataId, group, tenant);
      cr.setContent(content);
      String encryptedDataKey = LocalEncryptedDataKeyProcessor
              .getEncryptDataKeyFailover(agent.getName(), dataId, group, tenant);
      cr.setEncryptedDataKey(encryptedDataKey);
      configFilterChainManager.doFilter(null, cr);
      content = cr.getContent();
      return content;
   }
}
```

### Nacos客户端和服务端如何保持同步更新的

首先看NacosConfigService构造器
```java 
public NacosConfigService(Properties properties) throws NacosException {
        ValidatorUtils.checkInitParam(properties);
        String encodeTmp = properties.getProperty(PropertyKeyConst.ENCODE);
        if (StringUtils.isBlank(encodeTmp)) {
            this.encode = Constants.ENCODE;
        } else {
            this.encode = encodeTmp.trim();
        }
        initNamespace(properties);
        this.configFilterChainManager = new ConfigFilterChainManager(properties);
        // 初始化一个HttpAgent
        this.agent = new MetricsHttpAgent(new ServerHttpAgent(properties));
        this.agent.start();
        // 初始化一个ClientWorker
        this.worker = new ClientWorker(this.agent, this.configFilterChainManager, properties);
    }
    // ClientWorker 构造器中做了哪些事？
    public ClientWorker(final HttpAgent agent, final ConfigFilterChainManager configFilterChainManager,
            final Properties properties) {
        this.agent = agent;
        this.configFilterChainManager = configFilterChainManager;
        
        // Initialize the timeout parameter
        
        init(properties);
        // 
        this.executor = Executors.newScheduledThreadPool(1, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r);
                t.setName("com.alibaba.nacos.client.Worker." + agent.getName());
                t.setDaemon(true);
                return t;
            }
        });
        
        this.executorService = Executors
                .newScheduledThreadPool(Runtime.getRuntime().availableProcessors(), new ThreadFactory() {
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r);
                        t.setName("com.alibaba.nacos.client.Worker.longPolling." + agent.getName());
                        t.setDaemon(true);
                        return t;
                    }
                });
        // 
        this.executor.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                try {
                    // 延迟1毫秒，每隔10豪秒执行这个方法
                    checkConfigInfo();
                } catch (Throwable e) {
                    LOGGER.error("[" + agent.getName() + "] [sub-check] rotate check error", e);
                }
            }
        }, 1L, 10L, TimeUnit.MILLISECONDS);
    }
    
    public void checkConfigInfo() {
        // Dispatch tasks.
        // 这个listenerSize就是等于Nacos上有多少个配置文件需要监听
        int listenerSize = cacheMap.size();
        // Round up the longingTaskCount.
        //ParamUtil.getPerTaskConfigSize() 等于3000，所以在listenerSize不大于3000时，longingTaskCount等于1。
        int longingTaskCount = (int) Math.ceil(listenerSize / ParamUtil.getPerTaskConfigSize());
        // 只有当某个nacos client刚启动时或者配置文件个数大于3000时才会成立。
        if (longingTaskCount > currentLongingTaskCount) {
            for (int i = (int) currentLongingTaskCount; i < longingTaskCount; i++) {
                // The task list is no order.So it maybe has issues when changing.
                executorService.execute(new LongPollingRunnable(i));
            }
            currentLongingTaskCount = longingTaskCount;
        }
    }
    // LongPollingRunnable的run方法。
    @Override
        public void run() {
            
            List<CacheData> cacheDatas = new ArrayList<CacheData>();
            List<String> inInitializingCacheList = new ArrayList<String>();
            try {
                // check failover config
                for (CacheData cacheData : cacheMap.values()) {
                    if (cacheData.getTaskId() == taskId) {
                        cacheDatas.add(cacheData);
                        try {
                            // 检查本地配置文件，只有当使用了本地的配置文件才会真正执行。
                            checkLocalConfig(cacheData);
                            if (cacheData.isUseLocalConfigInfo()) {
                                cacheData.checkListenerMd5();
                            }
                        } catch (Exception e) {
                            LOGGER.error("get local config info error", e);
                        }
                    }
                }
                
                // check server config 这个是检查nacos服务端的配置是否更新
                List<String> changedGroupKeys = checkUpdateDataIds(cacheDatas, inInitializingCacheList);
                if (!CollectionUtils.isEmpty(changedGroupKeys)) {
                    LOGGER.info("get changedGroupKeys:" + changedGroupKeys);
                }
                
                for (String groupKey : changedGroupKeys) {
                    String[] key = GroupKey.parseKey(groupKey);
                    String dataId = key[0];
                    String group = key[1];
                    String tenant = null;
                    if (key.length == 3) {
                        tenant = key[2];
                    }
                    try {
                        // 依据服务端返回的更新的配置数据，再从服务端获取最新数据。
                        ConfigResponse response = getServerConfig(dataId, group, tenant, 3000L);
                        CacheData cache = cacheMap.get(GroupKey.getKeyTenant(dataId, group, tenant));
                        cache.setContent(response.getContent());
                        cache.setEncryptedDataKey(response.getEncryptedDataKey());
                        if (null != response.getConfigType()) {
                            cache.setType(response.getConfigType());
                        }
                        LOGGER.info("[{}] [data-received] dataId={}, group={}, tenant={}, md5={}, content={}, type={}",
                                agent.getName(), dataId, group, tenant, cache.getMd5(),
                                ContentUtils.truncateContent(response.getContent()), response.getConfigType());
                    } catch (NacosException ioe) {
                        String message = String
                                .format("[%s] [get-update] get changed config exception. dataId=%s, group=%s, tenant=%s",
                                        agent.getName(), dataId, group, tenant);
                        LOGGER.error(message, ioe);
                    }
                }
                for (CacheData cacheData : cacheDatas) {
                    if (!cacheData.isInitializing() || inInitializingCacheList
                            .contains(GroupKey.getKeyTenant(cacheData.dataId, cacheData.group, cacheData.tenant))) {
                        cacheData.checkListenerMd5();
                        cacheData.setInitializing(false);
                    }
                }
                inInitializingCacheList.clear();
                
                // 又向线程池提交了这个任务
                executorService.execute(this);
                
            } catch (Throwable e) {
                
                // If the rotation training task is abnormal, the next execution time of the task will be punished
                LOGGER.error("longPolling error : ", e);
                executorService.schedule(this, taskPenaltyTime, TimeUnit.MILLISECONDS);
            }
        }
    }
```
检查nacos服务端配置是否更新代码比较重要,追踪checkUpdateDataIds这个方法，后面调用了checkUpdateConfigStr这个方法。
```java 
List<String> checkUpdateConfigStr(String probeUpdateString, boolean isInitializingCacheList) throws Exception {

        Map<String, String> params = new HashMap<String, String>(2);
        params.put(Constants.PROBE_MODIFY_REQUEST, probeUpdateString);
        Map<String, String> headers = new HashMap<String, String>(2);
        headers.put("Long-Pulling-Timeout", "" + timeout);
        
        // told server do not hang me up if new initializing cacheData added in
        if (isInitializingCacheList) {
            headers.put("Long-Pulling-Timeout-No-Hangup", "true");
        }
        
        if (StringUtils.isBlank(probeUpdateString)) {
            return Collections.emptyList();
        }
        
        try {
            // In order to prevent the server from handling the delay of the client's long task,
            // increase the client's read timeout to avoid this problem.
            // 因为长轮询接口的响应时间比较久，所以增加了读取超时时间，避免超时
            long readTimeoutMs = timeout + (long) Math.round(timeout >> 1);
            // 长轮询接口路径：/v1/cs/configs/listener
            HttpRestResult<String> result = agent
                    .httpPost(Constants.CONFIG_CONTROLLER_PATH + "/listener", headers, params, agent.getEncode(),
                            readTimeoutMs);
            
            if (result.ok()) {
                setHealthServer(true);
                // 
                return parseUpdateDataIdResponse(result.getData());
            } else {
                setHealthServer(false);
                LOGGER.error("[{}] [check-update] get changed dataId error, code: {}", agent.getName(),
                        result.getCode());
            }
        } catch (Exception e) {
            setHealthServer(false);
            LOGGER.error("[" + agent.getName() + "] [check-update] get changed dataId exception", e);
            throw e;
        }
        return Collections.emptyList();
    }
```

先看长轮询接口/v1/cs/configs/listener的实现
```java 
    /**
 * The client listens for configuration changes.
 */
@PostMapping("/listener")
@Secured(action = ActionTypes.READ, parser = ConfigResourceParser.class)
public void listener(HttpServletRequest request, HttpServletResponse response)
        throws ServletException, IOException {
        request.setAttribute("org.apache.catalina.ASYNC_SUPPORTED", true);
        String probeModify = request.getParameter("Listening-Configs");
        if (StringUtils.isBlank(probeModify)) {
        throw new IllegalArgumentException("invalid probeModify");
        }

        probeModify = URLDecoder.decode(probeModify, Constants.ENCODE);

        Map<String, String> clientMd5Map;
        try {
        clientMd5Map = MD5Util.getClientMd5Map(probeModify);
        } catch (Throwable e) {
        throw new IllegalArgumentException("invalid probeModify");
        }

        // do long-polling
        // 这里是实现长轮询
        inner.doPollingConfig(request, response, clientMd5Map, probeModify.length());
        }
        
        
        // 长轮询实现
        public String doPollingConfig(HttpServletRequest request, HttpServletResponse response,
            Map<String, String> clientMd5Map, int probeRequestSize) throws IOException {
        
        // Long polling.
        if (LongPollingService.isSupportLongPolling(request)) {
           // 这个就很重要了，真正实现了长轮询机制
            longPollingService.addLongPollingClient(request, response, clientMd5Map, probeRequestSize);
            return HttpServletResponse.SC_OK + "";
        }
        
        // Compatible with short polling logic.
        List<String> changedGroups = MD5Util.compareMd5(request, response, clientMd5Map);
        
        // Compatible with short polling result.
        String oldResult = MD5Util.compareMd5OldResult(changedGroups);
        String newResult = MD5Util.compareMd5ResultString(changedGroups);
        
        String version = request.getHeader(Constants.CLIENT_VERSION_HEADER);
        if (version == null) {
            version = "2.0.0";
        }
        int versionNum = Protocol.getVersionNumber(version);
        
        // Before 2.0.4 version, return value is put into header.
        if (versionNum < START_LONG_POLLING_VERSION_NUM) {
            response.addHeader(Constants.PROBE_MODIFY_RESPONSE, oldResult);
            response.addHeader(Constants.PROBE_MODIFY_RESPONSE_NEW, newResult);
        } else {
            request.setAttribute("content", newResult);
        }
        
        Loggers.AUTH.info("new content:" + newResult);
        
        // Disable cache.
        response.setHeader("Pragma", "no-cache");
        response.setDateHeader("Expires", 0);
        response.setHeader("Cache-Control", "no-cache,no-store");
        response.setStatus(HttpServletResponse.SC_OK);
        return HttpServletResponse.SC_OK + "";
    }
```
长轮询真正的实现
```java 
 /**
     * Add LongPollingClient.
     *
     * @param req              HttpServletRequest.
     * @param rsp              HttpServletResponse.
     * @param clientMd5Map     clientMd5Map.
     * @param probeRequestSize probeRequestSize.
     */
    public void addLongPollingClient(HttpServletRequest req, HttpServletResponse rsp, Map<String, String> clientMd5Map,
            int probeRequestSize) {
        
        String str = req.getHeader(LongPollingService.LONG_POLLING_HEADER);
        String noHangUpFlag = req.getHeader(LongPollingService.LONG_POLLING_NO_HANG_UP_HEADER);
        String appName = req.getHeader(RequestUtil.CLIENT_APPNAME_HEADER);
        String tag = req.getHeader("Vipserver-Tag");
        int delayTime = SwitchService.getSwitchInteger(SwitchService.FIXED_DELAY_TIME, 500);
        
        // Add delay time for LoadBalance, and one response is returned 500 ms in advance to avoid client timeout.
        long timeout = Math.max(10000, Long.parseLong(str) - delayTime);
        if (isFixedPolling()) {
            timeout = Math.max(10000, getFixedPollingInterval());
            // Do nothing but set fix polling timeout.
        } else {
            long start = System.currentTimeMillis();
            List<String> changedGroups = MD5Util.compareMd5(req, rsp, clientMd5Map);
            if (changedGroups.size() > 0) {
                generateResponse(req, rsp, changedGroups);
                LogUtil.CLIENT_LOG.info("{}|{}|{}|{}|{}|{}|{}", System.currentTimeMillis() - start, "instant",
                        RequestUtil.getRemoteIp(req), "polling", clientMd5Map.size(), probeRequestSize,
                        changedGroups.size());
                return;
            } else if (noHangUpFlag != null && noHangUpFlag.equalsIgnoreCase(TRUE_STR)) {
                LogUtil.CLIENT_LOG.info("{}|{}|{}|{}|{}|{}|{}", System.currentTimeMillis() - start, "nohangup",
                        RequestUtil.getRemoteIp(req), "polling", clientMd5Map.size(), probeRequestSize,
                        changedGroups.size());
                return;
            }
        }
        String ip = RequestUtil.getRemoteIp(req);
        
        // Must be called by http thread, or send response.
        // 这个是实现长轮询的关键，AsyncContext是servlet3中的，可以将请求转给其他线程处理。
        final AsyncContext asyncContext = req.startAsync();
        
        // AsyncContext.setTimeout() is incorrect, Control by oneself
        asyncContext.setTimeout(0L);
        // 在ClientLongPolling 中处理请求，
        ConfigExecutor.executeLongPolling(
                new ClientLongPolling(asyncContext, clientMd5Map, ip, probeRequestSize, timeout, appName, tag));
    }
```
ClientLongPolling 的run方法：
```java 
@Override
        public void run() {
            // 注意这里是一个延迟任务，只会在 timeoutTime 时间之后执行一次。
             asyncTimeoutFuture = ConfigExecutor.scheduleLongPolling(new Runnable() {
                @Override
                public void run() {
                    try {
                        getRetainIps().put(ClientLongPolling.this.ip, System.currentTimeMillis());
                        
                        // Delete subsciber's relations.
                        // 在执行
                        allSubs.remove(ClientLongPolling.this);
                        // 判断是否是固定长轮询模式，默认是false
                        if (isFixedPolling()) {
                            LogUtil.CLIENT_LOG
                                    .info("{}|{}|{}|{}|{}|{}", (System.currentTimeMillis() - createTime), "fix",
                                            RequestUtil.getRemoteIp((HttpServletRequest) asyncContext.getRequest()),
                                            "polling", clientMd5Map.size(), probeRequestSize);
                            List<String> changedGroups = MD5Util
                                    .compareMd5((HttpServletRequest) asyncContext.getRequest(),
                                            (HttpServletResponse) asyncContext.getResponse(), clientMd5Map);
                            if (changedGroups.size() > 0) {
                                sendResponse(changedGroups);
                            } else {
                                 // 因为不是固定长轮询模式，所以会走到这里，返回的是null。
                                 // 因为在线程挂起的这段时间内，如果数据有变化，是通过allSubs中的监听器返回的。
                                 // 当超时时间到了，还没有数据变更，就没有检查数据是否变化了，所以就返回null。
                                sendResponse(null);
                            }
                        } else {
                            LogUtil.CLIENT_LOG
                                    .info("{}|{}|{}|{}|{}|{}", (System.currentTimeMillis() - createTime), "timeout",
                                            RequestUtil.getRemoteIp((HttpServletRequest) asyncContext.getRequest()),
                                            "polling", clientMd5Map.size(), probeRequestSize);
                                            
                            sendResponse(null);
                        }
                    } catch (Throwable t) {
                        LogUtil.DEFAULT_LOG.error("long polling error:" + t.getMessage(), t.getCause());
                    }
                    
                }
                
            }, timeoutTime, TimeUnit.MILLISECONDS);
            
            allSubs.add(this);
        }
```
Nacos长轮询是如何监听数据变更的？当有数据变更时，立即返回变更的数据。
在LongPollingService的构造器中，注册了一个订阅者，监听LocalDataChangeEvent。
```java 
public LongPollingService() {
        allSubs = new ConcurrentLinkedQueue<ClientLongPolling>();
        
        ConfigExecutor.scheduleLongPolling(new StatTask(), 0L, 10L, TimeUnit.SECONDS);
        
        // Register LocalDataChangeEvent to NotifyCenter.
        NotifyCenter.registerToPublisher(LocalDataChangeEvent.class, NotifyCenter.ringBufferSize);
        
        // Register A Subscriber to subscribe LocalDataChangeEvent.
        NotifyCenter.registerSubscriber(new Subscriber() {
            
            @Override
            public void onEvent(Event event) {
                if (isFixedPolling()) {
                    // Ignore.
                } else {
                    if (event instanceof LocalDataChangeEvent) {
                        LocalDataChangeEvent evt = (LocalDataChangeEvent) event;
                        // DataChangeTask：数据变更任务。
                        ConfigExecutor.executeLongPolling(new DataChangeTask(evt.groupKey, evt.isBeta, evt.betaIps));
                    }
                }
            }
            
            @Override
            public Class<? extends Event> subscribeType() {
                return LocalDataChangeEvent.class;
            }
        });
    }
    // DataChangeTask 的run方法：
    @Override
        public void run() {
            try {
                ConfigCacheService.getContentBetaMd5(groupKey);
                for (Iterator<ClientLongPolling> iter = allSubs.iterator(); iter.hasNext(); ) {
                    ClientLongPolling clientSub = iter.next();
                    //clientMd5Map 是在长轮询接口内有客户端传入的参数Listening-Configs生成的。
                    // 格式： user-service.properties+DEFAULT_GROUP+d65ea6d7-67a2-48ae-8b9e-00484b8ee664
                    // 故这里的判断的意思就是判断有没有获取groupKey的长轮询请求，如果有的话，再做处理。
                    if (clientSub.clientMd5Map.containsKey(groupKey)) {
                        // If published tag is not in the beta list, then it skipped.
                        if (isBeta && !CollectionUtils.contains(betaIps, clientSub.ip)) {
                            continue;
                        }
                        
                        // If published tag is not in the tag list, then it skipped.
                        if (StringUtils.isNotBlank(tag) && !tag.equals(clientSub.tag)) {
                            continue;
                        }
                        
                        getRetainIps().put(clientSub.ip, System.currentTimeMillis());
                        iter.remove(); // Delete subscribers' relationships.
                        LogUtil.CLIENT_LOG
                                .info("{}|{}|{}|{}|{}|{}|{}", (System.currentTimeMillis() - changeTime), "in-advance",
                                        RequestUtil
                                                .getRemoteIp((HttpServletRequest) clientSub.asyncContext.getRequest()),
                                        "polling", clientSub.clientMd5Map.size(), clientSub.probeRequestSize, groupKey);
                        // 发送Response，groupKey格式是user-service.properties%02DEFAULT_GROUP%02d65ea6d7-67a2-48ae-8b9e-00484b8ee664%01
                        clientSub.sendResponse(Arrays.asList(groupKey));
                    }
                }
            } catch (Throwable t) {
                LogUtil.DEFAULT_LOG.error("data change error: {}", ExceptionUtil.getStackTrace(t));
            }
        }
```
##总结
### Nacos客户端主动获取配置
- 当Nacos客户端启动时，会主动到Nacos服务端拉取配置信息并写入到本地缓存。以后需要配置时，先从本地缓存中查找，没有的话，再到服务端查找。

### Nacos客户端和服务端如何保持同步更新的
客户端会类似一直开启一个超时时间为30s的长轮询连接，当服务端在期间有数据返回，该连接会立刻响应，否则请求一直挂起。
服务端通过servlet3实现长轮询接口，将请求数据放入到一个队列中，当监听到数据变更，立即返回数据，否则请求线程会一直挂起。
长轮询接口返回有数据变更时，客户端会再到服务端获取完整的配置信息。