## Nacos 配置中心原理分析
### 客户端源码分析流程
1. 查看spring-cloud-starter-alibaba-nacos-config包下的META-INF目录下spring.factories
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
2. 很重要的两个自动配置类 NacosConfigBootstrapConfiguration和 NacosConfigAutoConfiguration，先分析NacosConfigAutoConfiguration
```java
public class NacosConfigAutoConfiguration {
    public NacosConfigAutoConfiguration() {
    }

    @Bean
    public NacosConfigProperties nacosConfigProperties(ApplicationContext context) {
        return context.getParent() != null && BeanFactoryUtils.beanNamesForTypeIncludingAncestors(context.getParent(), NacosConfigProperties.class).length > 0 ? (NacosConfigProperties)BeanFactoryUtils.beanOfTypeIncludingAncestors(context.getParent(), NacosConfigProperties.class) : new NacosConfigProperties();
    }

    @Bean
    public NacosRefreshProperties nacosRefreshProperties() {
        return new NacosRefreshProperties();
    }

    @Bean
    public NacosRefreshHistory nacosRefreshHistory() {
        return new NacosRefreshHistory();
    }

    @Bean
    public NacosConfigManager nacosConfigManager(NacosConfigProperties nacosConfigProperties) {
        return new NacosConfigManager(nacosConfigProperties);
    }

    @Bean
    public NacosContextRefresher nacosContextRefresher(NacosConfigManager nacosConfigManager, NacosRefreshHistory nacosRefreshHistory) {
        return new NacosContextRefresher(nacosConfigManager, nacosRefreshHistory);
    }
```

2. 介绍下注入的几个Bean：
   NacosContextRefresher：是一个监听器，实现了ApplicationListener接口，
   NacosRefreshProperties：已弃用
   NacosRefreshHistory：存放配置的历史数据
   NacosConfigManager: 持有一个属性 ConfigService service，根据nacos的配置信息创建该service。
```java 
   public NacosConfigManager(NacosConfigProperties nacosConfigProperties) {
   this.nacosConfigProperties = nacosConfigProperties;
   createConfigService(nacosConfigProperties);
   }
```

3. NacosContextRefresher 这个类比较重要，看下这个类。
```java 
public void onApplicationEvent(ApplicationReadyEvent event) {
        if (this.ready.compareAndSet(false, true)) {
            this.registerNacosListenersForApplications();
        }
    }
    // registerNacosListenersForApplications:
    private void registerNacosListenersForApplications() {
        if (this.isRefreshEnabled()) {
            //  获取所有配置信息
            Iterator var1 = NacosPropertySourceRepository.getAll().iterator();

            while(var1.hasNext()) {
                NacosPropertySource propertySource = (NacosPropertySource)var1.next();
                if (propertySource.isRefreshable()) {
                    String dataId = propertySource.getDataId();
                    // 注册监听器
                    this.registerNacosListener(propertySource.getGroup(), dataId);
                }
            }
        }
    }
    // NACOS_PROPERTY_SOURCE_REPOSITORY是一个 ConcurrentHashMap<String, NacosPropertySource> 结构。
    public static List<NacosPropertySource> getAll() {
        return new ArrayList(NACOS_PROPERTY_SOURCE_REPOSITORY.values());
    }
    
    // 注册监听器
    private void registerNacosListener(final String groupKey, final String dataKey) {
        String key = NacosPropertySourceRepository.getMapKey(dataKey, groupKey);
        Listener listener = (Listener)this.listenerMap.computeIfAbsent(key, (lst) -> {
            return new AbstractSharedListener() {
                public void innerReceive(String dataId, String group, String configInfo) {
                    // 刷新计数器加一
                    NacosContextRefresher.refreshCountIncrement();
                    // 将该条数据添加到历史备份中
                    NacosContextRefresher.this.nacosRefreshHistory.addRefreshRecord(dataId, group, configInfo);
                    // 发布刷新事件
                    NacosContextRefresher.this.applicationContext.publishEvent(new RefreshEvent(this, (Object)null, "Refresh Nacos config"));
                    if (NacosContextRefresher.log.isDebugEnabled()) {
                        NacosContextRefresher.log.debug(String.format("Refresh Nacos config group=%s,dataId=%s,configInfo=%s", group, dataId, configInfo));
                    }

                }
            };
        });
        try {
            // 最后放到CopyOnWriteArrayList<ManagerListenerWrap>中
            this.configService.addListener(dataKey, groupKey, listener);
        } catch (NacosException var6) {
            log.warn(String.format("register fail for nacos listener ,dataId=[%s],group=[%s]", dataKey, groupKey), var6);
        }
    }
    
```
4. 然后再分析 NacosConfigBootstrapConfiguration 。
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

6. NacosPropertySourceLocator:用于获取加载存放在Nacos配置中心的配置信息，locate方法会在项目初始化时执行。
```java 
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
		// load directly once by default，不使用文件后缀
		loadNacosDataIfPresent(compositePropertySource, dataIdPrefix, nacosGroup,
				fileExtension, true);
		// load with suffix, which have a higher priority than the default，使用文件后缀
		loadNacosDataIfPresent(compositePropertySource,
				dataIdPrefix + DOT + fileExtension, nacosGroup, fileExtension, true);
		// Loaded with profile, which have a higher priority than the suffix，通过环境又加载了一遍
		for (String profile : environment.getActiveProfiles()) {
			String dataId = dataIdPrefix + SEP1 + profile + DOT + fileExtension;
			loadNacosDataIfPresent(compositePropertySource, dataId, nacosGroup,
					fileExtension, true);
		}
	}
	
	private void loadNacosDataIfPresent(final CompositePropertySource composite,
			final String dataId, final String group, String fileExtension,
			boolean isRefreshable) {
		if (null == dataId || dataId.trim().length() < 1) {
			return;
		}
		if (null == group || group.trim().length() < 1) {
			return;
		}
		NacosPropertySource propertySource = this.loadNacosPropertySource(dataId, group,
				fileExtension, isRefreshable);
		this.addFirstPropertySource(composite, propertySource, false);
	}
	
	private NacosPropertySource loadNacosPropertySource(final String dataId,
			final String group, String fileExtension, boolean isRefreshable) {
		if (NacosContextRefresher.getRefreshCount() != 0) {
			if (!isRefreshable) {
				return NacosPropertySourceRepository.getNacosPropertySource(dataId,
						group);
			}
		}
		// 
		return nacosPropertySourceBuilder.build(dataId, group, fileExtension,
				isRefreshable);
	}
	
	NacosPropertySource build(String dataId, String group, String fileExtension,
			boolean isRefreshable) {
			// 加载nacos配置
		List<PropertySource<?>> propertySources = loadNacosData(dataId, group,
				fileExtension);
		NacosPropertySource nacosPropertySource = new NacosPropertySource(propertySources,
				group, dataId, new Date(), isRefreshable);
		NacosPropertySourceRepository.collectNacosPropertySource(nacosPropertySource);
		return nacosPropertySource;
	}
	// 
	private List<PropertySource<?>> loadNacosData(String dataId, String group,
			String fileExtension) {
		String data = null;
		try {
		    // 获取配置信息
			data = configService.getConfig(dataId, group, timeout);
			if (StringUtils.isEmpty(data)) {
				log.warn(
						"Ignore the empty nacos configuration and get it based on dataId[{}] & group[{}]",
						dataId, group);
				return Collections.emptyList();
			}
			if (log.isDebugEnabled()) {
				log.debug(String.format(
						"Loading nacos data, dataId: '%s', group: '%s', data: %s", dataId,
						group, data));
			}
			return NacosDataParserHandler.getInstance().parseNacosData(dataId, data,
					fileExtension);
		}
		catch (NacosException e) {
			log.error("get data from Nacos error,dataId:{} ", dataId, e);
		}
		catch (Exception e) {
			log.error("parse data from Nacos error,dataId:{},data:{}", dataId, data, e);
		}
		return Collections.emptyList();
	}
	
	 @Override
    public String getConfig(String dataId, String group, long timeoutMs) throws NacosException {
        return getConfigInner(namespace, dataId, group, timeoutMs);
    }
    
 
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
            // 本地配置找不到的话，到nacos服务端通过http接口查询配置 
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
```
7. 以上看的是 在Ncos客户端启动的时候加载nacos配置中心里的配置。下面看下Nacos客户端是如何感知Nacos服务端配置的变化的。首先看NacosConfigService构造器
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
8. 检查nacos服务端配置是否更新代码比较重要,追踪checkUpdateDataIds这个方法，后面调用了checkUpdateConfigStr这个方法。
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
9. 
### 服务端源码分析流程
1. 先看长轮询接口/v1/cs/configs/listener的实现
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
2. 长轮询真正的实现
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
3. ClientLongPolling 的run方法：
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
4. Nacos长轮询是如何监听数据变更的？当有数据变更时，立即返回变更的数据。
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
    // DataChangeTask的run方法：
    @Override
        public void run() {
            try {
                ConfigCacheService.getContentBetaMd5(groupKey);
                for (Iterator<ClientLongPolling> iter = allSubs.iterator(); iter.hasNext(); ) {
                    ClientLongPolling clientSub = iter.next();
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
                        // 发送Response
                        clientSub.sendResponse(Arrays.asList(groupKey));
                    }
                }
            } catch (Throwable t) {
                LogUtil.DEFAULT_LOG.error("data change error: {}", ExceptionUtil.getStackTrace(t));
            }
        }

```