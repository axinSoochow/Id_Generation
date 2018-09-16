import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 全局唯一ID生成结构如下(每部分用-分开):<br>
 * 0 - 00 - 0000000000 0000000000 0000000000 0000000000 0 - 0000000000 00 - 00000000 <br>
 * 1位标识，由于long基本类型在Java中是带符号的，最高位是符号位，正数是0，负数是1，所以id一般是正数，最高位是0<br>
 * 2位生成发布方式，0代表嵌入式发布、1代表中心服务器发布模式、2代表rest发布方式、3代表测试用<br>
 * 41位时间截(毫秒级)，注意，41位时间截不是存储当前时间的时间截，而是存储时间截的差值（当前时间截 - 开始时间截)
 * 得到的值），这里的的开始时间截，一般是我们的id生成器开始使用的时间，由我们程序来指定的（如下下面程序IdWorker类的startTime属性）。41位的时间截，可以使用69年，年T = (1L << 41) / (1000L * 60 * 60 * 24 * 365) = 69<br>
 * 12位序列，毫秒内的计数，12位的计数顺序号支持每个节点每毫秒(同一机器，同一时间截)产生4096个ID序号<br>
 * 8位的数据机器位，可以部署在256个节点，包括8位workerId<br>
 * 加起来刚好64位，为一个Long型。<br>
 * 优点是，整体上按照时间自增排序，并且整个分布式系统内不会产生ID碰撞(机器ID作区分)，并且效率较高，经测试，嵌入式的方式每秒能够产生40万ID左右。
 */
public final class IdGenerate {

    // ==============================Fields===========================================
    /** 开始时间截 (2018-01-01) */
    private final long twepoch = 1514736000000L;

    /** 机器id所占的位数 */
    private final long workerIdBits = 8L;

    /** 序列在id中占的位数 */
    private final long sequenceBits = 12L;

    /** 毫秒级别时间截占的位数 */
    private final long timestampBits = 41L;

    /** 生成发布方式所占的位数 */
    private final long getMethodBits = 2L;

    /** 支持的最大机器id，结果是255 (这个移位算法可以很快的计算出几位二进制数所能表示的最大十进制数) */
    private final long maxWorkerId = -1L ^ (-1L << workerIdBits);

    /** 生成序列向左移8位(8) */
    private final long sequenceShift = workerIdBits;

    /** 时间截向左移20位(12+8) */
    private final long timestampShift = sequenceBits + workerIdBits;

    /** 生成发布方式向左移61位(41+12+8) */
    private final long getMethodShift = timestampBits + sequenceBits  + workerIdBits;

    /** 工作机器ID(0~255) */
    private long workerId = 0L;

    /** 生成序列的掩码，这里为4095 (0b111111111111=0xfff=4095) */
    private final long sequenceMask = -1L ^ (-1L << sequenceBits);

    /** 毫秒内序列(0~4095) */
    private long sequence = 0L;

    /** 上次生成ID的时间截 */
    private long lastTimestamp = -1L;

    /** 2位生成发布方式，0代表嵌入式发布、1代表中心服务器发布模式、2代表rest发布方式、3代表保留未用 */
    private long getMethod = 0L;

    /** 成发布方式的掩码，这里为3 (0b11=0x3=3) */
    private long maxGetMethod = -1L ^ (-1L << getMethodBits);
    /** 重入锁*/
    private Lock lock = new ReentrantLock();
    //==============================Constructors=====================================
    /**
     * 构造函数
     * @param 发布方式 0代表嵌入式发布、1代表中心服务器发布模式、2代表rest发布方式、3代表保留未用 (0~3)
     * @param workerId 工作ID (0~255)
     */
    public IdGenerate(long getMethod, long workerId) {
        if (getMethod > maxGetMethod || getMethod < 0) {
            throw new IllegalArgumentException(String.format("getMethod can't be greater than %d or less than 0", maxGetMethod));
        }
        if (workerId > maxWorkerId || workerId < 0) {
            throw new IllegalArgumentException(String.format("worker Id can't be greater than %d or less than 0", maxWorkerId));
        }
        this.getMethod = getMethod;
        this.workerId = workerId;
    }

    public long[] nextId(int nums) {
        long[] ids = new long[nums];
        for (int i = 0; i < nums; i++) {
            ids[i] = nextId();
        }

        return ids;
    }

    // ==============================Methods==========================================
    /**
     * 获得下一个ID (该方法是线程安全的)
     * @return SnowflakeId
     */
    public long nextId() {
        long timestamp = timeGen();

        //如果当前时间小于上一次ID生成的时间戳，说明系统时钟回退过这个时候应当抛出异常
        if (timestamp < lastTimestamp) {
            throw new RuntimeException(
                    String.format("Clock moved backwards.  Refusing to generate id for %d milliseconds", lastTimestamp - timestamp));
        }

        //如果是同一时间生成的，则进行毫秒内序列
        if (lastTimestamp == timestamp) {
            try {
                lock.lock();
                sequence = (sequence + 1) & sequenceMask;
                //毫秒内序列溢出
                if (sequence == 0) {
                    //阻塞到下一个毫秒,获得新的时间戳
                    timestamp = tilNextMillis(lastTimestamp);
                }
            }finally {
                lock.unlock();
            }
        }
        //时间戳改变，毫秒内序列重置
        else {
            sequence = 0L;
        }

        //上次生成ID的时间截
        lastTimestamp = timestamp;

        //移位并通过或运算拼到一起组成64位的ID
        return  (getMethod << getMethodShift) // 生成方式占用2位，左移61位
                | ((timestamp - twepoch) << timestampShift) // 时间差占用41位，最多69年，左移20位
                | (sequence << sequenceShift) // 毫秒内序列，取值范围0-4095
                | workerId; // 工作机器，取值范围0-255
    }

    public String nextString() {
        return Long.toString(nextId());
    }

    public String[] nextString(int nums) {
        String[] ids = new String[nums];
        for (int i = 0; i < nums; i++) {
            ids[i] = nextString();
        }
        return ids;
    }

    public String nextCode(String prefix) {
        StringBuilder sb = new StringBuilder(prefix);
        long id = nextId();
        sb.append(id);
        return sb.toString();
    }

    /**
     * 此方法可以在前缀上增加业务标志
     * @param prefix
     * @param nums
     * @return
     */
    public String[] nextCode(String prefix, int nums) {
        String[] ids = new String[nums];
        for (int i = 0; i < nums; i++) {
            ids[i] = nextCode(prefix);
        }
        return ids;
    }

    public String nextHexString() {
        return Long.toHexString(nextId());
    }

    /**
     * 阻塞到下一个毫秒，直到获得新的时间戳
     * @param lastTimestamp 上次生成ID的时间截
     * @return 当前时间戳
     */
    protected long tilNextMillis(long lastTimestamp) {
        long timestamp = timeGen();
        while (timestamp <= lastTimestamp) {
            timestamp = timeGen();
        }
        return timestamp;
    }

    /**
     * 返回以毫秒为单位的当前时间
     * @return 当前时间(毫秒)
     */
    protected long timeGen() {
        return System.currentTimeMillis();
    }

    //==============================Test=============================================
    /**
     * 测试
     *
     *
     */

    public static void main(String[] args) {
        IdGenerate idGenerate = new IdGenerate(0, 0);
        int count = 50000;//线程数=count*count
        final long[][] times = new long[count][100];

        Thread[] threads = new Thread[count];
        for (int i = 0; i < threads.length; i++) {
            final int ip = i;
            threads[i] = new Thread() {
                @Override
                public void run() {
                    for (int j = 0; j <100; j++) {
                        long t1 = System.nanoTime();//该函数是返回纳秒的。1毫秒=1纳秒*1000000

                        idGenerate.nextId();//测试

                        long t = System.nanoTime() - t1;

                        times[ip][j] = t;//求平均
                    }
                }

            };
        }

        long lastMilis = System.currentTimeMillis();
        //逐个启动线程
        for (int i = 0; i < threads.length; i++) {
            try {
                threads[i].start();
                threads[i].join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

//        for (int i = 0; i < threads.length; i++) {
//            try {
//                threads[i].join();
//            } catch (InterruptedException e) {
//                e.printStackTrace();
//            }
//        }
        /**
         * 1、QPS：系统每秒处理的请求数（query per second）
           2、RT：系统的响应时间，一个请求的响应时间，也可以是一段时间的平均值
           3、最佳线程数量：刚好消耗完服务器瓶颈资源的临界线程数
            对于单线程：QPS=1000/RT
            对于多线程：QPS=1000*线程数量/RT
         */
        long time = System.currentTimeMillis() - lastMilis;
        System.out
                .println("QPS: "
                        + (1000*count /time));

        long sum = 0;
        long max = 0;
        for (int i = 0; i < times.length; i++) {
            for (int j = 0; j < times[i].length; j++) {
                sum += times[i][j];

                if (times[i][j] > max)
                    max = times[i][j];
            }
        }
        System.out.println("Sum(ms)"+time);
        System.out.println("AVG(ms): " + sum / 1000000 / (count*100));
        System.out.println("MAX(ms): " + max / 1000000);
    }
}