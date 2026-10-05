package br.com.garcia.payment.orderservice.events;

import org.springframework.amqp.rabbit.core.RabbitTemplate;

import br.com.garcia.payment.orderservice.config.RabbitConfig;
import tools.jackson.databind.ObjectMapper;

public class OrderEventPublisher {
    // publica OrderCreated logo após salvar o pedido como PENDING

    private final RabbitTemplate rabbitTemplate;

    public OrderEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void sendMessageToQueue(OrderCreated orderCreated) {
        String message = stringToJson(orderCreated);
        // Envia orderCreated em formato JSON para a fila order.queue
        rabbitTemplate.convertAndSend(RabbitConfig.ORDER_CREATED_QUEUE, message);
    } 

    public String stringToJson(OrderCreated orderCreated) {
        // Mapper jackson para converter o objeto OrderCreated em JSON
        ObjectMapper mapper = new ObjectMapper();
        return mapper.writeValueAsString(orderCreated);
    }

}
