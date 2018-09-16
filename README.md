# Id_Generation
全局唯一ID生成结构如下(每部分用-分开):&lt;br>  * 0 - 00 - 0000000000 0000000000 0000000000 0000000000 0 - 0000000000 00 - 00000000 &lt;br>  * 1位标识，由于long基本类型在Java中是带符号的，最高位是符号位，正数是0，负数是1，所以id一般是正数，最高位是0&lt;br>  * 2位生成发布方式，0代表嵌入式发布、1代表中心服务器发布模式、2代表rest发布方式、3代表测试用&lt;br>  * 41位时间截(毫秒级)，注意，41位时间截不是存储当前时间的时间截，而是存储时间截的差值（当前时间截 - 开始时间截)  * 得到的值），这里的的开始时间截，一般是我们的id生成器开始使用的时间，由我们程序来指定的（如下下面程序IdWorker类的startTime属性）。41位的时间截，可以使用69年，年T = (1L &lt;&lt; 41) / (1000L * 60 * 60 * 24 * 365) = 69&lt;br>  * 12位序列，毫秒内的计数，12位的计数顺序号支持每个节点每毫秒(同一机器，同一时间截)产生4096个ID序号&lt;br>  * 8位的数据机器位，可以部署在256个节点，包括8位workerId&lt;br>  * 加起来刚好64位，为一个Long型。&lt;br>  * 优点是，整体上按照时间自增排序，并且整个分布式系统内不会产生ID碰撞(机器ID作区分)，并且效率较高，经测试，嵌入式的方式每秒能够产生40万ID左右。 
