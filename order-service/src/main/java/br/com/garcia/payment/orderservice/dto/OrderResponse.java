package br.com.garcia.payment.orderservice.dto;

import br.com.garcia.payment.orderservice.model.OrderModel;
import java.math.BigDecimal;
import java.time.Instant;

public record OrderResponse(
    String payerName, 
    BigDecimal amount,
    String receiverEmail,
    String receiverAccountNumber,
    String description,
    String status,
    Instant createdAt
) {
    
    public OrderResponse(OrderModel orderModel){
        this(
            orderModel.getPayerName(),
            orderModel.getAmount(),
            orderModel.getReceiverEmail(),
            orderModel.getReceiverAccountNumber(),
            orderModel.getDescription(), 
            orderModel.getStatus().toString(),
            orderModel.getCreatedAt()
        ); 
    }

}
