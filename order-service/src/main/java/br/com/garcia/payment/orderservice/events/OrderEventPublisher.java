package br.com.garcia.payment.orderservice.events;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import br.com.garcia.payment.orderservice.config.RabbitConfig;

@Component
public class OrderEventPublisher {
    // publica OrderCreated logo após salvar o pedido como PENDING

    private final RabbitTemplate rabbitTemplate;

    public OrderEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void sendMessageToQueue(OrderCreated orderCreated) {
        // Envia orderCreated em formato JSON para a fila order.queue
        rabbitTemplate.convertAndSend(RabbitConfig.ORDER_CREATED_QUEUE, orderCreated);
    } 

}
