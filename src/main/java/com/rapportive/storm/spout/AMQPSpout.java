package com.rapportive.storm.spout;

import java.io.IOException;
import java.util.Map;

import org.apache.log4j.Logger;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.QueueingConsumer;
import backtype.storm.spout.Scheme;
import backtype.storm.spout.SpoutOutputCollector;
import backtype.storm.task.TopologyContext;
import backtype.storm.topology.IRichSpout;
import backtype.storm.topology.OutputFieldsDeclarer;

/**
 * Spout to feed messages into Storm from an AMQP exchange.
 * 
 * <p>
 * Each message published to the exchange that matches the supplied routing key
 * will be emitted as a Storm tuple. The message will be acked or rejected once
 * the topology has respectively fully processed or failed the corresponding
 * tuple.
 * </p>
 * 
 * <p>
 * This consumes messages from AMQP asynchronously, so it may receive messages
 * before Storm requests them as tuples; therefore it buffers messages in an
 * internal queue. To avoid this buffer growing large and consuming too much
 * RAM, set {@link #CONFIG_PREFETCH_COUNT}.
 * </p>
 * 
 * <p>
 * This should not currently be used where guaranteed message processing is
 * required, because it binds to the exchange using a temporary queue when the
 * topology calls <tt>open()</tt> on the spout. This means it will only receive
 * messages published to the exchange after the call to <tt>open()</tt>, and if
 * the spout worker restarts or the topology is killed, it will not receive any
 * messages published while the worker or topology is down.
 * </p>
 * 
 * <p>
 * For the same reason, this spout cannot currently be distributed among
 * multiple workers (each worker gets its own exclusive queue, so multiple
 * workers would each receive their own copy of every message).
 * </p>
 * 
 * <p>
 * Improvements are planned to overcome both these limitations and support
 * guaranteed message processing, distributed across any number of workers.
 * These improvements may require API changes (e.g. to specify the name of an
 * existing queue to consume, rather than an exchange to bind to).
 * </p>
 * 
 * @author Sam Stokes (sam@rapportive.com)
 */
public class AMQPSpout implements IRichSpout {
    private static final long serialVersionUID = 11258942292629263L;

    private static final Logger log = Logger.getLogger(AMQPSpout.class);

    /**
     * Storm config key to set the AMQP basic.qos prefetch-count parameter.
     * Defaults to 100.
     * 
     * <p>
     * This caps the number of messages outstanding (i.e. unacked) at a time
     * that will be sent to each spout worker. Increasing this will improve
     * throughput if the network roundtrip time to the AMQP broker is
     * significant compared to the time for the topology to process each
     * message; this will also increase the RAM requirements as the internal
     * message buffer grows.
     * </p>
     * 
     * <p>
     * AMQP allows a prefetch-count of zero, indicating unlimited delivery, but
     * that is not allowed here to avoid unbounded buffer growth.)
     * </p>
     */
    public static final String CONFIG_PREFETCH_COUNT = "amqp.prefetch.count";
    private static final long DEFAULT_PREFETCH_COUNT = 100;

    private static final long WAIT_FOR_NEXT_MESSAGE = 1L;

    private final String amqpHost;
    private final int amqpPort;
    private final String amqpUsername;
    private final String amqpPassword;
    private final String amqpVhost;
    private final String amqpExchange;
    private final String amqpRoutingKey;
    private final String amqpQueueName;

    private final Scheme serialisationScheme;

    private transient Connection amqpConnection;
    private transient Channel amqpChannel;
    private transient QueueingConsumer amqpConsumer;
    private transient String amqpConsumerTag;

    private SpoutOutputCollector collector;
    private long messageCount = 0;

    /**
     * Create a new AMQP spout. When
     * {@link #open(Map, TopologyContext, SpoutOutputCollector)} is called, it
     * will create a new server-named, exclusive, auto-delete queue, bind it to
     * the specified exchange on the specified server with the specified routing
     * key, and start consuming messages. It will use the provided
     * <tt>scheme</tt> to deserialise each AMQP message into a Storm tuple.
     * 
     * Most of the of the below variables may be null (or < 0 for numerical values) then
     * the default {@link ConnectionFactory} values will be used
     * 
     * @param host hostname of the AMQP broker node
     * @param port port number of the AMQP broker node
     * @param username username to log into to the broker
     * @param password password to authenticate to the broker
     * @param vhost vhost on the broker
     * @param exchange exchange to bind to **Cannot be null**
     * @param routingKey routing key for the binding
     * @param scheme {@link backtype.storm.spout.Scheme} used to deserialise
     *        each AMQP message into a Storm tuple
     * @param queue the name of the queue to use when pulling from AMQP
     */
    public AMQPSpout(String host, int port, String username, String password,
            String vhost, String exchange, String routingKey, Scheme scheme,
            String queue) {
        this.amqpHost = host;
        this.amqpPort = port;
        this.amqpUsername = username;
        this.amqpPassword = password;
        this.amqpVhost = vhost;
        this.amqpExchange = exchange;
        this.amqpRoutingKey = routingKey;
        this.amqpQueueName = queue;

        this.serialisationScheme = scheme;
    }

    public void ack(Object msgId) {
        System.out.println("Acking msg: "+msgId);
        if (msgId instanceof Long) {
            final long deliveryTag = (Long) msgId;
            if (amqpChannel != null) {
                try {
                    amqpChannel.basicAck(deliveryTag, false /* not multiple */);
                } catch (IOException e) {
                    log.warn("Failed to ack delivery-tag " + deliveryTag, e);
                }
            }
        } else {
            log.warn(String.format("don't know how to ack(%s: %s)", msgId
                    .getClass().getName(), msgId));
        }
    }

    public void close() {
        try {
            if (amqpChannel != null) {
                if (amqpConsumerTag != null) {
                    amqpChannel.basicCancel(amqpConsumerTag);
                }

                amqpChannel.close();
            }
        } catch (IOException e) {
            log.warn("Error closing AMQP channel", e);
        }

        try {
            if (amqpConnection != null) {
                amqpConnection.close();
            }
        } catch (IOException e) {
            log.warn("Error closing AMQP connection", e);
        }
    }

    public void fail(Object msgId) {
        System.out.println("Failing msg:"+msgId);
        if (msgId instanceof Long) {
            final long deliveryTag = (Long) msgId;
            if (amqpChannel != null) {
                try {
                    amqpChannel
                            .basicReject(deliveryTag, false /* don't requeue */);
                } catch (IOException e) {
                    log.warn("Failed to reject delivery-tag " + deliveryTag, e);
                }
            }
        } else {
            log.warn(String.format("don't know how to reject(%s: %s)", msgId
                    .getClass().getName(), msgId));
        }
    }

    public void nextTuple() {
        if (amqpConsumer != null) {
            try {
                final QueueingConsumer.Delivery delivery = amqpConsumer
                        .nextDelivery(WAIT_FOR_NEXT_MESSAGE);
                if (delivery == null)
                    return;
                final long deliveryTag = delivery.getEnvelope()
                        .getDeliveryTag();
                final byte[] message = delivery.getBody();
                log.debug("Sending message:"+new String(message));
                log.debug("Getting next tuple - " +(messageCount++));
                if(messageCount <0)
                {
                    log.debug("You just get a number of messages = max value of long! Wow! Reseting to 0");
                    this.messageCount = 0;
                }
                 collector.emit(serialisationScheme.deserialize(message),
                 deliveryTag);
            } catch (InterruptedException e) {
                // interrupted while waiting for message, big deal
            }
        }
    }

    public void open(@SuppressWarnings("rawtypes") Map config,
            TopologyContext context, SpoutOutputCollector collector) {
        Long prefetchCount = (Long) config.get(CONFIG_PREFETCH_COUNT);
        if (prefetchCount == null) {
            prefetchCount = DEFAULT_PREFETCH_COUNT;
            if (log.isInfoEnabled())
                log.info("Using default prefetch-count: " + prefetchCount);
        } else if (prefetchCount < 1) {
            throw new IllegalArgumentException(CONFIG_PREFETCH_COUNT
                    + " must be at least 1");
        }

        try {
            this.collector = collector;
            setupAMQP(prefetchCount.intValue());
        } catch (IOException e) {
            log.error("AMQP setup failed", e);
            // ensures that we fail out cleanly if the spout cannot open
            this.close();
            throw new RuntimeException(e);
        }
    }

    private void setupAMQP(int prefetchCount) throws IOException {
        final ConnectionFactory connectionFactory = new ConnectionFactory();

        // don't override values if we already have them running
        if (amqpHost != null)
            connectionFactory.setHost(amqpHost);
        if (amqpPort > 0)
            connectionFactory.setPort(amqpPort);
        if (amqpUsername != null)
            connectionFactory.setUsername(amqpUsername);
        if (amqpPassword != null)
            connectionFactory.setPassword(amqpPassword);
        if (amqpVhost != null)
            connectionFactory.setVirtualHost(amqpVhost);

        this.amqpConnection = connectionFactory.newConnection();
        this.amqpChannel = amqpConnection.createChannel();

        log.info("Setting basic.qos prefetch-count to " + prefetchCount);
        amqpChannel.basicQos(prefetchCount);

        // will throw an exception here if the exchange does not exist
        //amqpChannel.exchangeDeclarePassive(amqpExchange);

        final String queue;

        // if we passed in a queue name, we expect to be persistent on the
        // server
        // therefore we can just connect to it, and assume its there
        // if it isn't there yet, it will get created, so all the sprouts will
        // connect to it.
        if (this.amqpQueueName != null) {
            this.amqpChannel.queueBind(amqpQueueName, amqpExchange,
                    amqpRoutingKey);
            queue = amqpQueueName;
        }
        // if there is no queue name, just get a server side queue
        /*
         * This declares an exclusive, auto-delete, server-named queue. It'll be
         * deleted when this connection closes, e.g. if the spout worker process
         * gets restarted.
         */
        else
            queue = amqpChannel.queueDeclare().getQueue();

        amqpChannel.queueBind(queue, amqpExchange, amqpRoutingKey);

        this.amqpConsumer = new QueueingConsumer(amqpChannel);
        // start consuming, no auto-acking
        this.amqpConsumerTag = amqpChannel.basicConsume(queue, false,
                amqpConsumer);
    }

    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        declarer.declare(serialisationScheme.getOutputFields());
    }

    /**
     * Currently this spout can't be distributed, because each call to open()
     * creates a new queue and binds it to the exchange, so if there were
     * multiple workers they would each receive every message.
     */
    public boolean isDistributed() {
        // if we have a specific queue name to bind to, then we can scale out
        // the spouts
        // if not, just then stick with just 1:1 spout:queue.
        return this.amqpQueueName == null ? false : true;
    }
}
