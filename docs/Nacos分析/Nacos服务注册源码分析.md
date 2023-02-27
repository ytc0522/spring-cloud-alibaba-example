# Nacos 服务注册源码分析

## 版本介绍
- Nacos客户端和服务端版本都是1.4.2。
- spring-cloud-alibaba-starters版本2.2.6.RELEASE。

## 客户端核心源码
### 源码分析过程
- 首先查看spring-cloud-starter-alibaba-nacos-discovery依赖的自动装配文件spring.factories
```xml
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
</dependency>
```
```properties
org.springframework.boot.autoconfigure.EnableAutoConfiguration=\
  com.alibaba.cloud.nacos.discovery.NacosDiscoveryAutoConfiguration,\
  com.alibaba.cloud.nacos.ribbon.RibbonNacosAutoConfiguration,\
  com.alibaba.cloud.nacos.endpoint.NacosDiscoveryEndpointAutoConfiguration,\
  com.alibaba.cloud.nacos.registry.NacosServiceRegistryAutoConfiguration,\
  com.alibaba.cloud.nacos.discovery.NacosDiscoveryClientConfiguration,\
  com.alibaba.cloud.nacos.discovery.reactive.NacosReactiveDiscoveryClientConfiguration,\
  com.alibaba.cloud.nacos.discovery.configclient.NacosConfigServerAutoConfiguration,\
  com.alibaba.cloud.nacos.NacosServiceAutoConfiguration
org.springframework.cloud.bootstrap.BootstrapConfiguration=\
  com.alibaba.cloud.nacos.discovery.configclient.NacosDiscoveryClientConfigServiceBootstrapConfiguration
org.springframework.context.ApplicationListener=\
  com.alibaba.cloud.nacos.discovery.logging.NacosLoggingListener

```

- 找到Nacos服务注册自动配置类和入口NacosServiceRegistryAutoConfiguration，在该类中注入了一个Bean：
   NacosAutoServiceRegistration
```java 
	@Bean
	@ConditionalOnBean(AutoServiceRegistrationProperties.class)
	public NacosAutoServiceRegistration nacosAutoServiceRegistration(
			NacosServiceRegistry registry,
			AutoServiceRegistrationProperties autoServiceRegistrationProperties,
			NacosRegistration registration) {
		return new NacosAutoServiceRegistration(registry,
				autoServiceRegistrationProperties, registration);
	}
```
- 该类继承了 AbstractAutoServiceRegistration，实现了ApplicationListener的onApplicationEvent方法，在该方法中调用了bind方法。
```java
public class NacosAutoServiceRegistration
        extends AbstractAutoServiceRegistration<Registration>{}

public abstract class AbstractAutoServiceRegistration<R extends Registration>
        implements AutoServiceRegistration, ApplicationContextAware,
        ApplicationListener<WebServerInitializedEvent> {

   @Override
   @SuppressWarnings("deprecation")
   public void onApplicationEvent(WebServerInitializedEvent event) {
      bind(event);
   }

   @Deprecated
   public void bind(WebServerInitializedEvent event) {
      ApplicationContext context = event.getApplicationContext();
      if (context instanceof ConfigurableWebServerApplicationContext) {
         if ("management".equals(((ConfigurableWebServerApplicationContext) context)
                 .getServerNamespace())) {
            return;
         }
      }
      this.port.compareAndSet(0, event.getWebServer().getPort());
      this.start();
   }

   /**
    * 开始注册
    */
   public void start() {
      if (!isEnabled()) {
         if (logger.isDebugEnabled()) {
            logger.debug("Discovery Lifecycle disabled. Not starting");
         }
         return;
      }

      // only initialize if nonSecurePort is greater than 0 and it isn't already running
      // because of containerPortInitializer below
      if (!this.running.get()) {
         this.context.publishEvent(
                 new InstancePreRegisteredEvent(this, getRegistration()));
         
         // 注册
         register();
         if (shouldRegisterManagement()) {
            registerManagement();
         }
         this.context.publishEvent(
                 new InstanceRegisteredEvent<>(this, getConfiguration()));
         this.running.compareAndSet(false, true);
      }

   }

   /**
    * 注册本机客户端
    */
   protected void register() {
      this.serviceRegistry.register(getRegistration());
   }


   @Override
   public void register(Registration registration) {

      if (StringUtils.isEmpty(registration.getServiceId())) {
         log.warn("No service to register for nacos client...");
         return;
      }

      NamingService namingService = namingService();
      String serviceId = registration.getServiceId();
      String group = nacosDiscoveryProperties.getGroup();

      Instance instance = getNacosInstanceFromRegistration(registration);

      try {
          // NameService 注册该实例
         namingService.registerInstance(serviceId, group, instance);
         log.info("nacos registry, {} {} {}:{} register finished", group, serviceId,
                 instance.getIp(), instance.getPort());
      }
      catch (Exception e) {
         if (nacosDiscoveryProperties.isFailFast()) {
            log.error("nacos registry, {} register failed...{},", serviceId,
                    registration.toString(), e);
            rethrowRuntimeException(e);
         }
         else {
            log.warn("Failfast is false. {} register failed...{},", serviceId,
                    registration.toString(), e);
         }
      }
   }

    // 注册该实例
   @Override
   public void registerInstance(String serviceName, String groupName, Instance instance) throws NacosException {
      NamingUtils.checkInstanceIsLegal(instance);
      String groupedServiceName = NamingUtils.getGroupedName(serviceName, groupName);
      // 如果是临时节点的话，需要发送心跳
      if (instance.isEphemeral()) {
         BeatInfo beatInfo = beatReactor.buildBeatInfo(groupedServiceName, instance);
         beatReactor.addBeatInfo(groupedServiceName, beatInfo);
      }
      serverProxy.registerService(groupedServiceName, groupName, instance);
   }
}
    // 添加BeatInfo
   public void addBeatInfo(String serviceName, BeatInfo beatInfo) {
      NAMING_LOGGER.info("[BEAT] adding beat: {} to beat map.", beatInfo);
      String key = buildKey(serviceName, beatInfo.getIp(), beatInfo.getPort());
      BeatInfo existBeat = null;
      //fix #1733
      if ((existBeat = dom2Beat.remove(key)) != null) {
         existBeat.setStopped(true);
      }
      dom2Beat.put(key, beatInfo);
      // 定时发送心跳包给Nacos服务端
      executorService.schedule(new BeatTask(beatInfo), beatInfo.getPeriod(), TimeUnit.MILLISECONDS);
      MetricsMonitor.getDom2BeatSizeMonitor().set(dom2Beat.size());
   }

    // 发起注册请求
   public void registerService(String serviceName, String groupName, Instance instance) throws NacosException {

      NAMING_LOGGER.info("[REGISTER-SERVICE] {} registering service {} with instance: {}", namespaceId, serviceName,
              instance);

      final Map<String, String> params = new HashMap<String, String>(16);
      params.put(CommonParams.NAMESPACE_ID, namespaceId);
      params.put(CommonParams.SERVICE_NAME, serviceName);
      params.put(CommonParams.GROUP_NAME, groupName);
      params.put(CommonParams.CLUSTER_NAME, instance.getClusterName());
      params.put("ip", instance.getIp());
      params.put("port", String.valueOf(instance.getPort()));
      params.put("weight", String.valueOf(instance.getWeight()));
      params.put("enable", String.valueOf(instance.isEnabled()));
      params.put("healthy", String.valueOf(instance.isHealthy()));
      params.put("ephemeral", String.valueOf(instance.isEphemeral()));
      params.put("metadata", JacksonUtils.toJson(instance.getMetadata()));

      reqApi(UtilAndComs.nacosUrlInstance, params, HttpMethod.POST);
   }
```
#### 总结
- 通过实现ApplicationListener接口，在项目启动时执行注册的逻辑。
- 如果该客户端是临时节点，还需要定时发送心跳包，每隔5秒发送一次。
- 注册请求携带的数据包括该客户端的ip、port、weight、metadata等信息。

## 服务端核心源码
### 源码分析过程
1. 因为客户端将数据发送给接口/nacos/v1/ns/instance，故，这里我们查看该接口,该接口在nacos-naming模块的InstanceController中。
```java 
    @CanDistro
    @PostMapping
    @Secured(parser = NamingResourceParser.class, action = ActionTypes.WRITE)
    public String register(HttpServletRequest request) throws Exception {
        
        final String namespaceId = WebUtils
                .optional(request, CommonParams.NAMESPACE_ID, Constants.DEFAULT_NAMESPACE_ID);
        final String serviceName = WebUtils.required(request, CommonParams.SERVICE_NAME);
        NamingUtils.checkServiceNameFormat(serviceName);
        
        final Instance instance = parseInstance(request);
        // 调用的是ServiceManager的registerInstance方法
        serviceManager.registerInstance(namespaceId, serviceName, instance);
        return "ok";
    }
    
    // ServiceManager的注册方法。
     public void registerInstance(String namespaceId, String serviceName, Instance instance) throws NacosException {
     // 创建一个空的服务
     createEmptyService(namespaceId, serviceName, instance.isEphemeral());
     Service service = getService(namespaceId, serviceName);
     if (service == null) {
         throw new NacosException(NacosException.INVALID_PARAM,
                 "service not found, namespace: " + namespaceId + ", service: " + serviceName);
     }
     // 添加实例
     addInstance(namespaceId, serviceName, instance.isEphemeral(), instance);
 }
```
- 先看下如何创建空服务的
```java 
// 创建一个空的服务
    public void createEmptyService(String namespaceId, String serviceName, boolean local) throws NacosException {
      createServiceIfAbsent(namespaceId, serviceName, local, null);
   }
   
   // 实际上当服务不存在才会创建
   public void createServiceIfAbsent(String namespaceId, String serviceName, boolean local, Cluster cluster)
         throws NacosException {
    // 根据服务名称和namespaceId获取服务
     Service service = getService(namespaceId, serviceName);
     if (service == null) {
         
         Loggers.SRV_LOG.info("creating empty service {}:{}", namespaceId, serviceName);
         service = new Service();
         service.setName(serviceName);
         service.setNamespaceId(namespaceId);
         service.setGroupName(NamingUtils.getGroupName(serviceName));
         // now validate the service. if failed, exception will be thrown
         service.setLastModifiedMillis(System.currentTimeMillis());
         service.recalculateChecksum();
         // 服务下面是有集群的
         if (cluster != null) {
             cluster.setService(service);
             service.getClusterMap().put(cluster.getName(), cluster);
         }
         // 校验名称是否符合规则
         service.validate();
         // 添加服务并且初始化
         putServiceAndInit(service);
         if (!local) {
             addOrReplaceService(service);
         }
     }
 }
    // 添加服务并且初始化
     private void putServiceAndInit(Service service) throws NacosException {
       // 添加服务，这里用到了双重检索，防止重复添加
       // 放到了一个Map中，结构是Map(namespace, Map(group::serviceName, Service))
        putService(service);
        service = getService(service.getNamespaceId(), service.getName());
        // 服务初始
        service.init();
        
        // 这里就是需要将注册的服务信息同步给其他节点。
        // 临时节点和持久节点都监听了一遍，后面详细介绍具体逻辑
        consistencyService
                .listen(KeyBuilder.buildInstanceListKey(service.getNamespaceId(), service.getName(), true), service);
        consistencyService
                .listen(KeyBuilder.buildInstanceListKey(service.getNamespaceId(), service.getName(), false), service);
        Loggers.SRV_LOG.info("[NEW-SERVICE] {}", service.toJson());
    }
    
    // 服务初始化
     public void init() {
     // 每隔5秒执行一次心跳检测任务
     HealthCheckReactor.scheduleCheck(clientBeatCheckTask);
     for (Map.Entry<String, Cluster> entry : clusterMap.entrySet()) {
         entry.getValue().setService(this);
         entry.getValue().init();
     }
   }
   
   // 心跳检测任务
   @Override
    public void run() {
        try {
            if (!getDistroMapper().responsible(service.getName())) {
                return;
            }
            
            if (!getSwitchDomain().isHealthCheckEnabled()) {
                return;
            }
            
            List<Instance> instances = service.allIPs(true);
            
            // first set health status of instances:
            for (Instance instance : instances) {
               // 15秒未联系到客户端，标记不健康
                if (System.currentTimeMillis() - instance.getLastBeat() > instance.getInstanceHeartBeatTimeOut()) {
                    if (!instance.isMarked()) {
                        if (instance.isHealthy()) {
                            instance.setHealthy(false);
                            Loggers.EVT_LOG
                                    .info("{POS} {IP-DISABLED} valid: {}:{}@{}@{}, region: {}, msg: client timeout after {}, last beat: {}",
                                            instance.getIp(), instance.getPort(), instance.getClusterName(),
                                            service.getName(), UtilsAndCommons.LOCALHOST_SITE,
                                            instance.getInstanceHeartBeatTimeOut(), instance.getLastBeat());
                            getPushService().serviceChanged(service);
                            ApplicationUtils.publishEvent(new InstanceHeartbeatTimeoutEvent(this, instance));
                        }
                    }
                }
            }
            
            if (!getGlobalConfig().isExpireInstance()) {
                return;
            }
            
            // then remove obsolete instances:
            for (Instance instance : instances) {
                
                if (instance.isMarked()) {
                    continue;
                }
                // 30秒未联系到客户端，直接删除。
                if (System.currentTimeMillis() - instance.getLastBeat() > instance.getIpDeleteTimeout()) {
                    // delete instance
                    Loggers.SRV_LOG.info("[AUTO-DELETE-IP] service: {}, ip: {}", service.getName(),
                            JacksonUtils.toJson(instance));
                    deleteIp(instance);
                }
            }
            
        } catch (Exception e) {
            Loggers.SRV_LOG.warn("Exception while processing client beat time out.", e);
        }
    }
```
- 然后再看下添加实例这个方法
```java 
    public void addInstance(String namespaceId, String serviceName, boolean ephemeral, Instance... ips)
        throws NacosException {

        String key = KeyBuilder.buildInstanceListKey(namespaceId, serviceName, ephemeral);
         // 先获取这个服务
        Service service = getService(namespaceId, serviceName);
    synchronized (service) {
         // 拷贝旧的实例列表，添加新实例到列表中。、
        List<Instance> instanceList = addIpAddresses(service, ephemeral, ips);

        Instances instances = new Instances();
        instances.setInstanceList(instanceList);
         // 完成对实例状态更新后，则会用新列表直接覆盖旧实例列表
        consistencyService.put(key, instances);
        }
    }
```
### 总结
- 通过Http接口接受来自客户端的注册请求，首先在 serviceMap（结构是Map(namespace, Map(group::serviceName, Service))）中查找该服务是否已经存在。
- 如果不存在则创建一个新的服务，添加到该serviceMap中，然后初始化一个定时任务去定期检测该服务的心跳时间，15秒内未联系到该客户端，则标记不健康，30秒内未联系，直接删除。
- 通过上面步骤就有了该服务，然后将该客户端实例添加到Map<String, Datum> dataMap中，dataMap的key是用来标记唯一的服务，value主要用来存放所有的实例信息。
- 然后发布一个服务改变的事件到一个阻塞队列中（BlockingQueue<Pair<String, DataOperation>>）去，通过一直消费该事件完成集群节点之间的数据同步。
- 服务注册时集群数据同步见文档 Nacos集群同步源码分析.md