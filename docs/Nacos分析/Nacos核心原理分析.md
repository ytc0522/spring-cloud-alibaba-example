
# Nacos核心源码分析
## Nacos 服务注册源码分析

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
6. 在com.alibaba.nacos.client.naming.NacosNamingService.registerInstance方法中，判断是否是临时实例，如果是的话，定时发送心跳信息。
![img_6.png](img_6.png)
7. 在方法com.alibaba.nacos.client.naming.net.NamingProxy.registerService中执行具体注册的逻辑：发起POST请求将客户端数据发送给nacos服务端。
![img_7.png](img_7.png)
### 客户端注册流程图

![img_5.png](img_5.png)

### 服务端核心源码



## Nacos服务发现原理分析
### 客户端原理


### 服务端原理

## Nacos 配置中心原理分析
### 客户端

### 服务端