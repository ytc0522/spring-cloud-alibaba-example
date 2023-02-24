## Nacos服务发现源码分析
### 客户端源码分析流程
1. Nacos 服务发现的入口类是 NacosDiscoveryClient，该类实现了Spring Cloud DiscoveryClient接口。
   该类主要由两个方法，getServices和 getInstances，前者用来获取全部的服务名称，后者根据服务id获取指定的实例信息。
2. getServices的逻辑比较简单，就是调用服务端接口，把所有注册的服务的名称获取回来，我们先看getInstances方法。

![img_24.png](img_24.png)

3. 获取指定的服务的实例信息时，首先判断是否订阅， 如果订阅了，会先从本地缓存中找，
   如果没有再到nacos服务端中去查询并更新到本地缓存中去。

![img_26.png](img_26.png)

4. 到服务端主动查询实例的代码如下：

![img_30.png](img_30.png)

6. 本地缓存是如何和服务端保持同步更新的？
7. 首先客户端通过PushReceiver开启一个udp连接到服务端，实时获取由服务端推送的数据，并将数据更新到本地缓存。
   然后发送ack信息到服务端，以便让服务端判断是否重试。

   ![img_27.png](img_27.png)
8.

![img_28.png](img_28.png)
还有一个定时任务，每隔1秒去从服务端获取最新的服务信息并更新缓存。
![img_29.png](img_29.png)

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