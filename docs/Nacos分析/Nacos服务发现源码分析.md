# Nacos服务发现源码分析
## 版本介绍
- Nacos客户端和服务端版本都是1.4.2。
- spring-cloud-alibaba-starters版本2.2.6.RELEASE。

## 源码分析
### Nacos客户端主动获取服务列表
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

HostReactor 类
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

### Nacos服务端主动推送服务列表数据
在HostReactor类的构造方法中初始化了一个PushReceiver，主要用来完成开启一个UDP连接到服务端，好让服务端实时推送数据。
```java 
    public HostReactor(NamingProxy serverProxy, BeatReactor beatReactor, String cacheDir, boolean loadCacheAtStart,
            boolean pushEmptyProtection, int pollingThreadCount) {
        // init executorService
        this.executor = new ScheduledThreadPoolExecutor(pollingThreadCount, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r);
                thread.setDaemon(true);
                thread.setName("com.alibaba.nacos.client.naming.updater");
                return thread;
            }
        });
        
        this.beatReactor = beatReactor;
        this.serverProxy = serverProxy;
        this.cacheDir = cacheDir;
        if (loadCacheAtStart) {
            this.serviceInfoMap = new ConcurrentHashMap<String, ServiceInfo>(DiskCache.read(this.cacheDir));
        } else {
            this.serviceInfoMap = new ConcurrentHashMap<String, ServiceInfo>(16);
        }
        this.pushEmptyProtection = pushEmptyProtection;
        this.updatingMap = new ConcurrentHashMap<String, Object>();
        this.failoverReactor = new FailoverReactor(this, cacheDir);
        // 这里创建了一个PushReceiver
        this.pushReceiver = new PushReceiver(this);
        this.notifier = new InstancesChangeNotifier();
        
        NotifyCenter.registerToPublisher(InstancesChangeEvent.class, 16384);
        NotifyCenter.registerSubscriber(notifier);
    }
```
PushReceiver：
```java
public class PushReceiver implements Runnable, Closeable {

   private static final Charset UTF_8 = Charset.forName("UTF-8");

   private static final int UDP_MSS = 64 * 1024;

   private ScheduledExecutorService executorService;

   private DatagramSocket udpSocket;

   private HostReactor hostReactor;

   private volatile boolean closed = false;

   public static String getPushReceiverUdpPort() {
      return System.getenv(PropertyKeyConst.PUSH_RECEIVER_UDP_PORT);
   }

   // 构造方法
   public PushReceiver(HostReactor hostReactor) {
      try {
         this.hostReactor = hostReactor;
         // 获取UDP端号
         String udpPort = getPushReceiverUdpPort();
         if (StringUtils.isEmpty(udpPort)) {
            this.udpSocket = new DatagramSocket();
         } else {
            this.udpSocket = new DatagramSocket(new InetSocketAddress(Integer.parseInt(udpPort)));
         }
         this.executorService = new ScheduledThreadPoolExecutor(1, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
               Thread thread = new Thread(r);
               thread.setDaemon(true);
               thread.setName("com.alibaba.nacos.naming.push.receiver");
               return thread;
            }
         });
         // 用线程池执行该线程
         this.executorService.execute(this);
      } catch (Exception e) {
         NAMING_LOGGER.error("[NA] init udp socket failed", e);
      }
   }

   // run方法：
   @Override
   public void run() {
      while (!closed) {
         try {

            // byte[] is initialized with 0 full filled by default
            byte[] buffer = new byte[UDP_MSS];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            // 一直阻塞接受来自服务端的数据包
            udpSocket.receive(packet);

            String json = new String(IoUtils.tryDecompress(packet.getData()), UTF_8).trim();
            NAMING_LOGGER.info("received push data: " + json + " from " + packet.getAddress().toString());

            // 将接受到的结果转换为PushPacket
            PushPacket pushPacket = JacksonUtils.toObj(json, PushPacket.class);
            String ack;
            if ("dom".equals(pushPacket.type) || "service".equals(pushPacket.type)) {
               hostReactor.processServiceJson(pushPacket.data);

               // send ack to server
               ack = "{\"type\": \"push-ack\"" + ", \"lastRefTime\":\"" + pushPacket.lastRefTime + "\", \"data\":"
                       + "\"\"}";
            } else if ("dump".equals(pushPacket.type)) {
               // dump data to server
               ack = "{\"type\": \"dump-ack\"" + ", \"lastRefTime\": \"" + pushPacket.lastRefTime + "\", \"data\":"
                       + "\"" + StringUtils.escapeJavaScript(JacksonUtils.toJson(hostReactor.getServiceInfoMap()))
                       + "\"}";
            } else {
               // do nothing send ack only
               ack = "{\"type\": \"unknown-ack\"" + ", \"lastRefTime\":\"" + pushPacket.lastRefTime
                       + "\", \"data\":" + "\"\"}";
            }
            // 发送ACK
            udpSocket.send(new DatagramPacket(ack.getBytes(UTF_8), ack.getBytes(UTF_8).length,
                    packet.getSocketAddress()));
         } catch (Exception e) {
            if (closed) {
               return;
            }
            NAMING_LOGGER.error("[NA] error while receiving push data", e);
         }
      }
   }
}
```

在服务端通过PushService推送数据
```java
public class PushService implements ApplicationContextAware, ApplicationListener<ServiceChangeEvent> {

   @Autowired
   private SwitchDomain switchDomain;

   private ApplicationContext applicationContext;

   private static final long ACK_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(10L);

   private static final int MAX_RETRY_TIMES = 1;

   private static volatile ConcurrentMap<String, Receiver.AckEntry> ackMap = new ConcurrentHashMap<>();

   private static ConcurrentMap<String, ConcurrentMap<String, PushClient>> clientMap = new ConcurrentHashMap<>();

   private static volatile ConcurrentMap<String, Long> udpSendTimeMap = new ConcurrentHashMap<>();

   public static volatile ConcurrentMap<String, Long> pushCostMap = new ConcurrentHashMap<>();

   private static int totalPush = 0;

   private static int failedPush = 0;

   private static DatagramSocket udpSocket;

   private static ConcurrentMap<String, Future> futureMap = new ConcurrentHashMap<>();

   static {
      try {
         udpSocket = new DatagramSocket();

         Receiver receiver = new Receiver();

         Thread inThread = new Thread(receiver);
         inThread.setDaemon(true);
         inThread.setName("com.alibaba.nacos.naming.push.receiver");
         inThread.start();

         GlobalExecutor.scheduleRetransmitter(() -> {
            try {
               removeClientIfZombie();
            } catch (Throwable e) {
               Loggers.PUSH.warn("[NACOS-PUSH] failed to remove client zombie");
            }
         }, 0, 20, TimeUnit.SECONDS);

      } catch (SocketException e) {
         Loggers.SRV_LOG.error("[NACOS-PUSH] failed to init push service");
      }
   }

   @Override
   public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
      this.applicationContext = applicationContext;
   }

   // 通过监听ServiceChangeEvent，主动推送服务列表数据
   @Override
   public void onApplicationEvent(ServiceChangeEvent event) {
       // 获取事件中的服务
      Service service = event.getService();
      String serviceName = service.getName();
      String namespaceId = service.getNamespaceId();
      // 延迟一秒后执行一次
      Future future = GlobalExecutor.scheduleUdpSender(() -> {
         try {
            Loggers.PUSH.info(serviceName + " is changed, add it to push queue.");
            ConcurrentMap<String, PushClient> clients = clientMap
                    .get(UtilsAndCommons.assembleFullServiceName(namespaceId, serviceName));
            if (MapUtils.isEmpty(clients)) {
               return;
            }

            Map<String, Object> cache = new HashMap<>(16);
            long lastRefTime = System.nanoTime();
            for (PushClient client : clients.values()) {
               if (client.zombie()) {
                  Loggers.PUSH.debug("client is zombie: " + client.toString());
                  clients.remove(client.toString());
                  Loggers.PUSH.debug("client is zombie: " + client.toString());
                  continue;
               }
               // 组装要推送的数据 
               Receiver.AckEntry ackEntry;
               Loggers.PUSH.debug("push serviceName: {} to client: {}", serviceName, client.toString());
               String key = getPushCacheKey(serviceName, client.getIp(), client.getAgent());
               byte[] compressData = null;
               Map<String, Object> data = null;
               if (switchDomain.getDefaultPushCacheMillis() >= 20000 && cache.containsKey(key)) {
                  org.javatuples.Pair pair = (org.javatuples.Pair) cache.get(key);
                  compressData = (byte[]) (pair.getValue0());
                  data = (Map<String, Object>) pair.getValue1();

                  Loggers.PUSH.debug("[PUSH-CACHE] cache hit: {}:{}", serviceName, client.getAddrStr());
               }
               // 组装数据
               if (compressData != null) {
                  ackEntry = prepareAckEntry(client, compressData, data, lastRefTime);
               } else {
                  ackEntry = prepareAckEntry(client, prepareHostsData(client), lastRefTime);
                  if (ackEntry != null) {
                     cache.put(key, new org.javatuples.Pair<>(ackEntry.origin.getData(), ackEntry.data));
                  }
               }

               Loggers.PUSH.info("serviceName: {} changed, schedule push for: {}, agent: {}, key: {}",
                       client.getServiceName(), client.getAddrStr(), client.getAgent(),
                       (ackEntry == null ? null : ackEntry.key));
               // UDP推送
               udpPush(ackEntry);
            }
         } catch (Exception e) {
            Loggers.PUSH.error("[NACOS-PUSH] failed to push serviceName: {} to client, error: {}", serviceName, e);

         } finally {
            futureMap.remove(UtilsAndCommons.assembleFullServiceName(namespaceId, serviceName));
         }

      }, 1000, TimeUnit.MILLISECONDS);

      futureMap.put(UtilsAndCommons.assembleFullServiceName(namespaceId, serviceName), future);

   }
}
```

#### 总结
- Nacos 服务发现的流程是：先从本地缓存中找，找不到再查询Nacos服务端并更新到本地缓存。
- 为了让Nacos客户端本地缓存和nacos服务端的服务列表保持同步，采用了两种方式：一种是客户端定时去服务端拉取，一种是nacos服务端主动推送给客户端更新的数据。


