package com.dream.iot.redis.consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.core.GenericTypeResolver;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class RedisConsumerOperaManager implements InitializingBean, DisposableBean {

    private RedisProperties config;

    private Executor executor;
    /**
     * 所有消费对象列表
     */
    private List<RedisConsumerOpera> operas;

    /**
     * 用来处理需要阻塞的消费对线
     */
    private ExecutorService blockExecutorService;

    List<RedisConsumerWrapper> consumers = new ArrayList<>();
    /**
     * 需要阻塞消费的消费列表
     */
    List<RedisConsumerWrapper> blockConsumers = new ArrayList<>();
    /**
     * 正在执行消费的非阻塞消费列表
     */
    List<RedisConsumerWrapper> execConsumers = Collections.synchronizedList(new ArrayList<>());
    /**
     * 用来处理消费对象
     */
    private ExecutorService consumerExecutorService = Executors.newFixedThreadPool(1);

    private Logger logger = LoggerFactory.getLogger(getClass());

    public RedisConsumerOperaManager(List<RedisConsumerOpera> operas, Executor executor, RedisProperties config) {
        this.operas = operas;
        this.config = config;
        this.executor = executor;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        operas.forEach(opera -> {
            if(opera instanceof BlockConsumerOpera) {
                List<RedisConsumer> blocks = ((BlockConsumerOpera) opera).blocks();
                if(!CollectionUtils.isEmpty(blocks)) {
                    List<RedisConsumerWrapper> wrappers = blocks.stream().map(item ->
                            new RedisConsumerWrapper(item, opera)).collect(Collectors.toList());
                    blockConsumers.addAll(wrappers);
                } else {
                    List<RedisConsumer> consumers = opera.consumers();
                    if(!CollectionUtils.isEmpty(consumers)) {
                        List<RedisConsumerWrapper> wrappers = consumers.stream().map(item ->
                                new RedisConsumerWrapper(item, opera)).collect(Collectors.toList());

                        this.consumers.addAll(wrappers);
                    }
                }
            } else {
                List<RedisConsumer> consumers = opera.consumers();
                if(!CollectionUtils.isEmpty(consumers)) {
                    List<RedisConsumerWrapper> wrappers = consumers.stream().map(item ->
                            new RedisConsumerWrapper(item, opera)).collect(Collectors.toList());

                    this.consumers.addAll(wrappers);
                }
            }
        });

        if(!CollectionUtils.isEmpty(this.consumers)) {
            this.execConsumers.addAll(this.consumers);

            // 执行消费处理算法任务
            consumerExecutorService.execute(new ConsumerHandleTask());
        }

        if(!CollectionUtils.isEmpty(blockConsumers)) {
            blockExecutorService = Executors.newFixedThreadPool(blockConsumers.size());
            blockConsumers.forEach(item -> {
                blockExecutorService.execute(new BlockHandleTask(item));
            });
        }

    }

    @Override
    public void destroy() throws Exception {
        if(blockExecutorService != null) {
            blockExecutorService.shutdownNow();
        }

        if(!consumerExecutorService.isShutdown()) {
            consumerExecutorService.shutdownNow();
        }
    }

    /**
     * 阻塞处理任务
     */
    class BlockHandleTask implements Runnable {

        private RedisConsumerWrapper consumerWrapper;

        public BlockHandleTask(RedisConsumerWrapper consumerWrapper) {
            this.consumerWrapper = consumerWrapper;
        }

        @Override
        public void run() {
            long timeout = config.getTimeout().getSeconds();
            if(timeout > 5) { // 比指定的超时时间减少2秒
                timeout = timeout - 2;
            }

            RedisConsumer consumer = consumerWrapper.consumer;
            BlockConsumerOpera consumerOpera = (BlockConsumerOpera)consumerWrapper.consumerOpera;
            while (true) {
                try {
                    List invoker = consumerOpera.invoker(consumer.getKey(), timeout);
                    // 如果已经生产队列里面已经有数据
                    if(!CollectionUtils.isEmpty(invoker)) {

                        // 先消费刚阻塞返回的数据
                        consumerWrapper.consumer(invoker);

                        // 继续读取数据列表
                        List list = consumerOpera.invoker(consumer.getKey(), consumer.maxSize());
                        /**
                         * 消费数据的回调, 并且返回消费成功的
                         * @see ListConsumer#consumer(List)
                         */
                        final Object r = consumerWrapper.consumer(list);
                        consumerOpera.remove(consumer.getKey(), r);
                    }
                } catch (Exception e) {
                    logger.error("Redis消费管理异常({}) - key: {} - 消费对象: {}", e.getMessage(), consumer.getKey(), consumer.getClass(), e);
                    continue;
                }
            }
        }
    }

    /**
     * 此任务会一直循环执行任务<br>
     *     1. 如果执行列表还有任务则将任务加入任务队列执行, 并且将任务从当前可执行列表里面移除, 等到此任务执行完之后再将任务加入到可执行列表
     *     2. 如果执行列表暂无任务, 将暂时休眠等待10秒
     */
    class ConsumerHandleTask implements Runnable {

        @Override
        public void run() {
            for (;;) {
                try {
                    int notFinishCount = 0; // 未消费完成的数量
                    for (RedisConsumerWrapper item : execConsumers) {
                        if(item.isFinish()) { // 如消费完成, 继续消费
                            item.setFinish(false); // 还未开始消费

                            // 加入消费任务队列
                            executor.execute(new ConsumerTask(item));
                        } else {
                            notFinishCount ++;
                        }
                    }

                    // 如果全部未完成消费, 则消费处理任务休眠5秒, 让出此线程的时间片段
                    if(notFinishCount == execConsumers.size()) {
                        if(logger.isDebugEnabled()) {
                            logger.debug("Redis消费算法 所有消费任务都未完成休眠5秒 - 为完成RedisConsumer对象: {} - 总RedisConsumer对象: {} - "
                                    , notFinishCount, execConsumers.size());
                        }

                        Thread.sleep(5 * 1000);
                    }
                } catch (InterruptedException e) {
                    return; // 线程中断 直接返回
                }catch (Exception e) {
                    logger.error("Redis消费任务算法异常({})", e.getMessage(), e);
                } finally {
                    continue;
                }
            }
        }
    }

    class ConsumerTask implements Runnable {

        private RedisConsumerWrapper consumerWrapper;

        public ConsumerTask(RedisConsumerWrapper consumerWrapper) {
            this.consumerWrapper = consumerWrapper;
        }

        @Override
        public void run() {
            RedisConsumer consumer = consumerWrapper.consumer;

            try {
                // 开始消费
                List invoker = consumerWrapper.consumerOpera.invoker(consumer.getKey(), consumer.maxSize());

                // 执行消费回调
                if(!CollectionUtils.isEmpty(invoker)) {
                    final Object r = consumerWrapper.consumer(invoker);
                    // 消费完之后移除消费的数据
                    consumerWrapper.consumerOpera.remove(consumer.getKey(), r);
                }
            } catch (Exception e) {
                logger.error("Redis消费管理异常({}) - key: {} - 消费对象: {}", e.getMessage(), consumer.getKey(), consumer.getClass(), e);
            } finally {
                this.consumerWrapper.setFinish(true); // 声明已经消费完成, 可以进行下一次消费任务
            }
        }
    }

    class RedisConsumerWrapper implements RedisConsumer {

        // 此消费对象是否完成完成
        private volatile boolean finish = true;

        // 值类型
        private Class<?> valueClazz;
        private RedisConsumer consumer;
        private RedisConsumerOpera consumerOpera;

        public RedisConsumerWrapper(RedisConsumer consumer, RedisConsumerOpera consumerOpera) {
            this.consumer = consumer;
            this.consumerOpera = consumerOpera;

            Class<?>[] typeArguments = GenericTypeResolver.resolveTypeArguments(consumer.getClass(), RedisConsumer.class);
            valueClazz = typeArguments != null && typeArguments.length != 0 ? typeArguments[0] : null;
        }

        @Override
        public String getKey() {
            return consumer.getKey();
        }

        @Override
        public Object consumer(List v) {
            if(valueClazz != null) {
                v = consumerOpera.deserialize(v, valueClazz);
            }

            return consumer.consumer(v);
        }

        public RedisConsumer getConsumer() {
            return consumer;
        }

        public RedisConsumerOpera getConsumerOpera() {
            return consumerOpera;
        }

        public Class<?> getValueClazz() {
            return valueClazz;
        }

        public boolean isFinish() {
            return finish;
        }

        public void setFinish(boolean finish) {
            this.finish = finish;
        }
    }

}
