**简介**

这里是 [『香蕉大魔王』](https://github.com/zhangjun1998) 的 Caffeine 源码解析，[原始项目地址](https://github.com/zhangjun1998/caffeine) 在这。
技术暂时还比较拙劣，可能有很多理解不到位或有出入的地方，所以源码注释仅供参考哈，如有错误还请指正，错误很多的话就别说了，请把一些优秀的博客地址贴给我，我爱学习。

**已看和待看的代码如下：**

+ [x] Caffeine 缓存构造器
+ [x] Cache 缓存顶级接口
+ [x] LoadingCache 自动加载式缓存接口
+ [x] LocalManualCache 手动加载式缓存接口
+ [x] LocalLoadingCache 继承了 LocalManualCache + LoadingCache 接口，
+ [x] LocalCache
+ [x] BoundedLocalCache 有界缓存，里面有内部类 BoundedLocalManualCache、BoundedLocalLoadingCache、BoundedLocalAsyncCache、BoundedLocalAsyncLoadingCache 实现了各种缓存
+ [x] Buffer 读缓冲区接口
+ [x] StripedBuffer 带状缓冲区实现类
+ [x] BoundedBuffer 有界缓冲区，继承自 StripedBuffer，里面有内部类 RingBuffer，最终实现了带状环形缓冲区
+ [x] MpscGrowableArrayQueue 写缓冲区实现类
+ [x] FrequencySketch 对 Count-Min Sketch 算法的实现
+ [x] AbstractLinkedDeque 顺序队列的抽象类，给顺序访问队列和顺序写队列提供骨架
+ [x] AccessOrderDeque 顺序访问队列，LRU，用做 window、main 区域的三个访问队列，方便实现按照 W-TinyLFU 进行缓存驱逐 和 expireAfterAccess() 过期
+ [x] WriteOrderDeque 顺序写队列，用于实现 expireAfterWrite 的过期策略
+ [x] TimerWheel 时间轮，用于实现 expireAfterWriter() 的自定义过期策略
+ [ ] Ticker 时间源，用在自定义过期策略中，方便时间轮定位 
+ [ ] ...



**联系方式：**

+ 邮箱：zhangjun_java@163.com
+ 微信：rzy-zj

如要联系我请备注来意，不知道怎么备注的我提供个模板：「oh，你的 Caffeine 源码解析的太好了，加个好友瞧一瞧」。好，祝大家技术进步，生活快乐。
