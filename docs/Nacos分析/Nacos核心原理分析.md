
# Nacos核心源码分析
## Nacos 服务注册源码分析
本次看的是Nacos客户端和服务端版本都是1.4.2。

![img_8.png](img_8.png)


### 客户端核心源码
#### 源码分析过程
1. 首先查看查看spring-cloud-starter-alibaba-nacos-discovery依赖的自动装配文件spring.factories
```aidl
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
</dependency>

```
![a](https://upload-images.jianshu.io/upload_images/27061397-2e117f3d3ab3ee26.png?imageMogr2/auto-orient/strip|imageView2/2/w/879/format/webp)

2. 找到Nacos服务注册自动配置类
![img.png](img.png)

3. 查看NacosServiceRegistryAutoConfiguration类源码,在该类中注入了一个Bean：
   NacosAutoServiceRegistration
![img_1.png](img_1.png)
4. 该类继承了AbstractAutoServiceRegistration，实现了ApplicationListener的onApplicationEvent方法，在该方法中调用了bind方法。
![img_2.png](img_2.png)
5. 一直追踪下去可以进入到com.alibaba.cloud.nacos.registry.NacosServiceRegistry.register
![img_4.png](img_4.png)
6. 在com.alibaba.nacos.client.naming.NacosNamingService.registerInstance方法中，判断是否是临时实例，如果是的话，每隔5秒定时发送心跳信息。
![img_6.png](img_6.png)
7. 在方法com.alibaba.nacos.client.naming.net.NamingProxy.registerService中执行具体注册的逻辑：发起POST请求将客户端数据发送给nacos服务端。
![img_7.png](img_7.png)
### 客户端注册流程图

![img_5.png](img_5.png)

### 服务端核心源码
#### 源码分析过程
1. 因为客户端将数据发送给接口/nacos/v1/ns/instance，故，这里我们查看该接口,该接口在nacos-naming模块的InstanceController中。
![img_9.png](img_9.png)
2. ServiceManager类中一个属性serviceMap，Map(namespace, Map(group::serviceName, Service))
   如果是第一次的话，新建一个空的服务放入注册表内并初始化。具体代码见putServiceAndInit方法。

![img_11.png](img_11.png)
putService方法：将服务存入Map中。
![img_10.png](img_10.png)

3. 初始化操作逻辑在service.init()方法中，首先会开启一个定时任务，每隔5秒中去检查客户端的最近的心跳时间是否超过默认的超时时间15秒，如果超过了，
设置健康状态为false；如果超过了30秒就会将该实例删除。
![img_12.png](img_12.png)
4. 将服务的实例信息保存起来，并且同步给Nacos集群的其他节点。addIPAddress中，会拷贝旧的实例列表，添加新实例到列表中。在第3步中，完成对实例状态更新后，则会用新列表直接覆盖旧实例列表。而在更新过程中，旧实例列表不受影响，用户依然可以读取。
![img_13.png](img_13.png)

5. 集群数据同步代码：

![img_15.png](img_15.png)
onPut()方法：

![img_16.png](img_16.png)

6. notifier 是一个 com.alibaba.nacos.naming.consistency.ephemeral.distro.DistroConsistencyServiceImpl 内部类。
并且在DistroConsistencyServiceImpl初始化时就通过一个单线程开始一个定时任务，不断从阻塞队列中获取任务，执行服务列表的更新
![img_17.png](img_17.png)

7. 在定时任务中处理代码：

![img_18.png](img_18.png)

8. handle()方法如下，当服务变更了出发service类中的onChange方法。

![img_19.png](img_19.png)

9. 在distroProtocol.sync 同步集群中节点的数据。

![img_20.png](img_20.png)

同步失败的话，会重试处理。属于AP模式。
![img_21.png](img_21.png)

10. 通过Http的形式给集群中其他节点同步数据。

![img_23.png](img_23.png)

![img_22.png](img_22.png)


### 总结
1、使用了很多的异步任务和队列。
Nacos内部会将服务注册的任务放入阻塞队列，采用线程池异步来完成实例更新，从而提高并发写能力。
2. 免并发读写的冲突
Nacos在更新实例列表时，会采用CopyOnWrite技术，首先将Old实例列表拷贝一份，然后更新拷贝的实例列表，再用更新后的实例列表来覆盖旧的实例列表。

## Nacos服务发现原理分析
### 客户端原理




### 服务端原理

## Nacos 配置中心原理分析
### 客户端

### 服务端