package br.com.garcia.payment.orderservice.config;

import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuração da "topologia" do RabbitMQ usada pelo order-service.
 *
 * Visão geral do fluxo:
 *
 *   [Producer]  --(mensagem + routing key)-->  [Exchange]  --(binding)-->  [Queue]  -->  [Consumer]
 *   order-service                             payments.exchange          payment.order-created.queue   payment-service
 *
 * Conceitos-chave:
 * - Producer: quem publica a mensagem. Ele NUNCA manda direto para uma fila,
 *   sempre publica em uma exchange, informando uma routing key.
 * - Exchange: o "roteador/carteiro". Recebe a mensagem e decide para qual(is)
 *   fila(s) ela vai, com base nas regras de binding.
 * - Queue (fila): onde a mensagem fica armazenada até um consumer lê-la.
 * - Binding: a "regra" que liga uma exchange a uma fila ("mensagens com a
 *   routing key X devem ir para a fila Y").
 * - Routing key: uma "etiqueta" que o producer coloca na mensagem para a
 *   exchange saber como roteá-la.
 *
 * Declarando tudo isso como @Bean, o Spring AMQP (via RabbitAdmin/AmqpAdmin)
 * cria automaticamente a exchange, a fila e o binding no broker, caso ainda
 * não existam. Se já existirem com as mesmas configurações, nada acontece
 * (a declaração é idempotente).
 */
@Configuration
public class RabbitConfig {

    // Nome da exchange. Uma única exchange pode concentrar vários tipos de
    // evento do domínio de pagamentos (order.created, order.paid, ...).
    public static final String EXCHANGE = "payments.exchange";

    // Nome da fila que armazenará os eventos de "pedido criado".
    // Convenção usada: <quem consome>.<evento>.queue
    public static final String ORDER_CREATED_QUEUE = "payment.order-created.queue";

    // Routing key usada ao publicar o evento de pedido criado.
    // Em topic exchanges, o padrão é usar palavras separadas por ponto.
    public static final String ORDER_CREATED_KEY = "order.created";

    /**
     * Declara a exchange do tipo TOPIC.
     * Escolhemos topic pela flexibilidade: no futuro outro serviço pode criar
     * uma fila ligada com "order.#" e receber todos os eventos de pedido,
     * sem precisar alterar o producer.
     * durable(true): a exchange sobrevive a um restart do broker RabbitMQ.
     */
    @Bean TopicExchange paymentsExchange(){
        return ExchangeBuilder.topicExchange(EXCHANGE).durable(true).build();
    }

    /**
     * Declara a fila de eventos "pedido criado".
     *
     * QueueBuilder.durable(...): a fila (a definição dela) sobrevive a restart
     * do broker. Atenção: para as MENSAGENS também sobreviverem, elas precisam
     * ser publicadas como persistentes (o RabbitTemplate do Spring já usa
     * delivery mode PERSISTENT por padrão).
     */
    @Bean Queue orderCreatedQueue() {
        return QueueBuilder.durable(ORDER_CREATED_QUEUE).build();
    }

    /**
     * Declara o binding: "toda mensagem que chegar na payments.exchange com
     * routing key 'order.created' deve ser entregue na payment.order-created.queue".
     *
     * Os parâmetros (Queue, TopicExchange) são injetados pelo Spring a partir
     * dos @Beans acima. Obs.: se houver mais de uma Queue/Exchange no contexto,
     * será preciso diferenciar (pelo nome do parâmetro ou com @Qualifier).
     */
    @Bean Binding orderCreatedBinding(Queue queue, TopicExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange).with(ORDER_CREATED_KEY);
    }

    /**
     * Força a declaração da topologia (exchange, fila e binding) logo na
     * subida da aplicação.
     *
     * Por padrão, o Spring AMQP é "preguiçoso": só declara tudo isso no broker
     * quando a primeira conexão é aberta (ex.: ao publicar a primeira mensagem).
     * Com este ApplicationRunner, chamamos amqpAdmin.initialize() assim que a
     * aplicação sobe, então a fila já aparece no painel do RabbitMQ
     * (http://localhost:15672) mesmo antes de qualquer pedido ser criado.
     */
    @Bean ApplicationRunner declararFilas(AmqpAdmin amqpAdmin) {
        return args -> amqpAdmin.initialize();
    }
    
    @Bean MessageConverter jsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }
    
}
