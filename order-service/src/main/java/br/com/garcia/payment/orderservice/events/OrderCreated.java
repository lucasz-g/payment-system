package br.com.garcia.payment.orderservice.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import br.com.garcia.payment.orderservice.model.OrderModel;

public record OrderCreated(
        UUID eventId, 
        UUID orderId,
        String payerName, 
        BigDecimal amount,
        String receiverEmail,
        String receiverAccountNumber,
        String description, 
        String status,
        Instant createdAt,
        Instant occurredAt
    ) {
    public OrderCreated(OrderModel orderModel) {
        this(
            
                UUID.randomUUID(),
                orderModel.getOrderId(),
                orderModel.getPayerName(), 
                orderModel.getAmount(),
                orderModel.getReceiverEmail(),
                orderModel.getReceiverAccountNumber(),
                orderModel.getDescription(), 
                orderModel.getStatus().toString(),
                orderModel.getCreatedAt(), 
                Instant.now()
            );
    }
}
