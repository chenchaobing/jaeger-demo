package se.lars.ofe

import io.grpc.ManagedChannel
import io.opentracing.Tracer
import io.opentracing.contrib.ClientTracingInterceptor
import io.vertx.core.AbstractVerticle
import io.vertx.core.AsyncResult
import io.vertx.core.json.JsonObject
import io.vertx.grpc.VertxChannelBuilder
import io.vertx.kafka.client.consumer.KafkaConsumer
import io.vertx.kafka.client.consumer.KafkaConsumerRecord
import io.vertx.kafka.client.producer.KafkaProducer
import io.vertx.kafka.client.producer.KafkaProducerRecord
import org.apache.kafka.clients.consumer.ConsumerConfig.*
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerConfig.BOOTSTRAP_SERVERS_CONFIG
import se.lars.common.delay
import se.lars.common.kafkaHeaderFormat
import se.lars.common.logger
import se.lars.translation.TranslationGrpc
import se.lars.translation.TranslationGrpc.TranslationVertxStub
import se.lars.translation.TranslationReply
import se.lars.translation.TranslationRequest


class OFEVerticle(private val tracer: Tracer, private val ofeId: String) : AbstractVerticle() {
    private val log = logger<OFEVerticle>()
    private lateinit var consumer: KafkaConsumer<String, JsonObject>
    private lateinit var producer: KafkaProducer<String, JsonObject>
    private lateinit var channel: ManagedChannel
    private lateinit var service: TranslationVertxStub

    override fun start() {
        val consumerProps = mapOf(
            BOOTSTRAP_SERVERS_CONFIG to "localhost:9092",
            KEY_DESERIALIZER_CLASS_CONFIG to "org.apache.kafka.common.serialization.StringDeserializer",
            VALUE_DESERIALIZER_CLASS_CONFIG to "io.vertx.kafka.client.serialization.JsonObjectDeserializer",
            ENABLE_AUTO_COMMIT_CONFIG to "true",
            GROUP_ID_CONFIG to ofeId
        )
        consumer = KafkaConsumer.create<String, JsonObject>(vertx, consumerProps)
        consumer.handler(this::handleEvent)
        consumer.subscribe("ot")

        val producerProps = mapOf(
            BOOTSTRAP_SERVERS_CONFIG to "localhost:9092",
            ProducerConfig.ACKS_CONFIG to "1",
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to "org.apache.kafka.common.serialization.StringSerializer",
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to "io.vertx.kafka.client.serialization.JsonObjectSerializer"
        )
        producer = KafkaProducer.create<String, JsonObject>(vertx, producerProps)

        channel = VertxChannelBuilder
            .forAddress(vertx, "localhost", 8080)
            .usePlaintext(true)
            .build()
        val tracingInterceptor = ClientTracingInterceptor(this.tracer)
//        service = TranslationGrpc.newVertxStub(channel)
        service = TranslationGrpc.newVertxStub(tracingInterceptor.intercept(channel))
    }

    override fun stop() {
        producer.close()
        consumer.close();
    }

    private fun handleEvent(record: KafkaConsumerRecord<String, JsonObject>) {
        tracer.buildSpan("processOddChange")
            .asChildOf(tracer.extract(kafkaHeaderFormat, record.record().headers()))
            .startActive()
            .use { span ->
                log.info("Processing key=${record.key()},value=${record.value()},partition=${record.partition()},offset=${record.offset()},headers=${record.record().headers()}")
                processData()
                val capture = span.capture()
                translate { result ->
                    val spanCont = capture.activate()
                    if (result.succeeded()) {
                        log.info("Translated")

                    } else {
                        log.error("Failed to translate: ${result.cause().message}")
                    }
                    //sendToPush(record.value())
                    spanCont.deactivate()
                }
            }
    }


    private fun sendToPush(message: JsonObject) {
        val record = KafkaProducerRecord.create<String, JsonObject>("push", "aa", message)
        tracer.inject(tracer.activeSpan().context(), kafkaHeaderFormat, record.record().headers())
        producer.write(record)
    }


    private fun processData() {
        tracer.buildSpan("processData")
            .startActive()
            .use {
                delay()
            }
    }

    private fun translate(callback: (AsyncResult<TranslationReply>) -> Unit) {
        //val capture = tracer.activeSpan().capture()
        service.translate(TranslationRequest.newBuilder().setTextId(1).build()) { result ->
            //  capture.activate()
            callback(result)
        }
    }
}
