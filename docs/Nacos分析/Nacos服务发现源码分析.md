## Nacos服务发现源码分析
## 版本介绍
- Nacos客户端和服务端版本都是1.4.2。
- spring-cloud-alibaba-starters版本2.2.6.RELEASE。

### 客户端源码分析
spring-cloud-starter-alibaba-nacos-discovery包中是使用Ribbon作为负载均衡的，所以需要从Nacos和Ribbon整合开始看起。
首先Nacos Client 发起服务调用请求后，后面是通过NacosServerList来获取服务提供方信息。
```java 
   // 获取所有的服务提供方实例信息
	private List<NacosServer> getServers() {
		try {
			String group = discoveryProperties.getGroup();
			List<Instance> instances = discoveryProperties.namingServiceInstance()
					.selectInstances(serviceId, group, true);
			return instancesToServerList(instances);
		} catch (Exception e) {
			throw new IllegalStateException(
					"Can not get service instances from nacos, serviceId=" + serviceId,
					e);
		}
	}
   // 中间还有几个重载方法，已省略
    
    @Override
    public List<Instance> selectInstances(String serviceName, String groupName, List<String> clusters, boolean healthy,
            boolean subscribe) throws NacosException {
        
        ServiceInfo serviceInfo;
        // subscribe = true
        if (subscribe) {
            serviceInfo = hostReactor.getServiceInfo(NamingUtils.getGroupedName(serviceName, groupName),
                    StringUtils.join(clusters, ","));
        } else {
            serviceInfo = hostReactor
                    .getServiceInfoDirectlyFromServer(NamingUtils.getGroupedName(serviceName, groupName),
                            StringUtils.join(clusters, ","));
        }
        return selectInstances(serviceInfo, healthy);
    }
```

调用的是 HostReactor 类。
```java 
public ServiceInfo getServiceInfo(final String serviceName, final String clusters) {
        
        NAMING_LOGGER.debug("failover-mode: " + failoverReactor.isFailoverSwitch());
        String key = ServiceInfo.getKey(serviceName, clusters);
        if (failoverReactor.isFailoverSwitch()) {
            return failoverReactor.getService(key);
        }
        // 先从本地内存获取
        ServiceInfo serviceObj = getServiceInfo0(serviceName, clusters);
        
        if (null == serviceObj) {
            // 如果缓存中没有，则创建一个放入到缓存中
            serviceObj = new ServiceInfo(serviceName, clusters);
            serviceInfoMap.put(serviceObj.getKey(), serviceObj);
            
            updatingMap.put(serviceName, new Object());
            
            // 更新服务
            updateServiceNow(serviceName, clusters);
            updatingMap.remove(serviceName);
            
        } else if (updatingMap.containsKey(serviceName)) {
            
            if (UPDATE_HOLD_INTERVAL > 0) {
                // hold a moment waiting for update finish
                synchronized (serviceObj) {
                    try {
                        serviceObj.wait(UPDATE_HOLD_INTERVAL);
                    } catch (InterruptedException e) {
                        NAMING_LOGGER
                                .error("[getServiceInfo] serviceName:" + serviceName + ", clusters:" + clusters, e);
                    }
                }
            }
        }
        // 每秒中执行一次
        scheduleUpdateIfAbsent(serviceName, clusters);
        
        return serviceInfoMap.get(serviceObj.getKey());
    }
    
    
    
     private ServiceInfo getServiceInfo0(String serviceName, String clusters) {
         String key = ServiceInfo.getKey(serviceName, clusters);
          return serviceInfoMap.get(key);
   }
   
   // 
    public void updateService(String serviceName, String clusters) throws NacosException {
        ServiceInfo oldService = getServiceInfo0(serviceName, clusters);
        try {
            // 向nacos server的/nacos/v1/ns/instance/list路径发送HTTP GET请求
            String result = serverProxy.queryList(serviceName, clusters, pushReceiver.getUdpPort(), false);
            
            if (StringUtils.isNotEmpty(result)) {
               // 将result转换为serviceInfo，并放入serviceInfoMap中
                processServiceJson(result);
            }
        } finally {
            if (oldService != null) {
                synchronized (oldService) {
                    oldService.notifyAll();
                }
            }
        }
    }
```

还有个定时任务调度。
```java 
    public void scheduleUpdateIfAbsent(String serviceName, String clusters) {
        if (futureMap.get(ServiceInfo.getKey(serviceName, clusters)) != null) {
            return;
        }
        
        synchronized (futureMap) {
            if (futureMap.get(ServiceInfo.getKey(serviceName, clusters)) != null) {
                return;
            }
            // 添加更新任务，每秒执行一次。
            ScheduledFuture<?> future = addTask(new UpdateTask(serviceName, clusters));
            futureMap.put(ServiceInfo.getKey(serviceName, clusters), future);
        }
    }
```

UpdateTask 的 run 方法：

```java 
   // 就是将nacos服务端的数据拉到本地更新
   @Override
  public void run() {
      long delayTime = DEFAULT_DELAY;
      
      try {
          ServiceInfo serviceObj = serviceInfoMap.get(ServiceInfo.getKey(serviceName, clusters));
          
          if (serviceObj == null) {
              updateService(serviceName, clusters);
              return;
          }
          
          if (serviceObj.getLastRefTime() <= lastRefTime) {
              updateService(serviceName, clusters);
              serviceObj = serviceInfoMap.get(ServiceInfo.getKey(serviceName, clusters));
          } else {
              // if serviceName already updated by push, we should not override it
              // since the push data may be different from pull through force push
              refreshOnly(serviceName, clusters);
          }
          
          lastRefTime = serviceObj.getLastRefTime();
          
          if (!notifier.isSubscribed(serviceName, clusters) && !futureMap
                  .containsKey(ServiceInfo.getKey(serviceName, clusters))) {
              // abort the update task
              NAMING_LOGGER.info("update task is stopped, service:" + serviceName + ", clusters:" + clusters);
              return;
          }
          if (CollectionUtils.isEmpty(serviceObj.getHosts())) {
              incFailCount();
              return;
          }
          delayTime = serviceObj.getCacheMillis();
          resetFailCount();
      } catch (Throwable e) {
          incFailCount();
          NAMING_LOGGER.warn("[NA] failed to update serviceName: " + serviceName, e);
      } finally {
          executor.schedule(this, Math.min(delayTime << failCount, DEFAULT_DELAY * 60), TimeUnit.MILLISECONDS);
      }
  }
```

#### Nacos服务端主动推送服务列表数据



#### 总结
- Nacos 服务发现的流程是：先从本地缓存中找，找不到再查询Nacos服务端并更新到本地缓存。
- Nacos客户端为了让本地缓存和nacos服务端的服务列表保持同步，采用了两种方式：一种是客户端定时去服务端拉取，一种是nacos服务端主动推送给客户端更新的数据。

7. 首先客户端通过PushReceiver开启一个udp连接到服务端，实时获取由服务端推送的数据，并将数据更新到本地缓存。
   然后发送ack信息到服务端，以便让服务端判断是否重试。


### 服务端源码
1. 从客户端源码可以看出，客户端的服务发现由两种模式，主动拉取和被动接收。
2. 主动拉取要调用的接口是：/ns/v1/instance/list/,主要逻辑就是添加一个pushclient，然后获取实例信息并返回。
   com.alibaba.nacos.naming.controllers.InstanceController#list

![img_31.png](img_31.png)
尝试添加一个pushClient。
![img_32.png](img_32.png)

3. 主动推送的代码：com.alibaba.nacos.naming.push.PushService 中，在静态代码块中做了如下的事：

![img_33.png](img_33.png)

4. 该类实现了ApplicationListener，在实现的方法onApplicationEvent中

![img_34.png](img_34.png)

![img_35.png](img_35.png)

5. udpPush()方法：

![img_36.png](img_36.png)