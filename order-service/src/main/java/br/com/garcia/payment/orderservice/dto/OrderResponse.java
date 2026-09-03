package br.com.garcia.payment.orderservice.dto;

import br.com.garcia.payment.orderservice.model.OrderModel;
import java.math.BigDecimal;

public record OrderResponse(
    BigDecimal amount,
    String receiverEmail,
    String receiverAccountNumber,
    String status,
    String createdAt
) {
    
    public OrderResponse(OrderModel orderModel){
        this(
            orderModel.getAmount(),
            orderModel.getReceiverEmail(),
            orderModel.getReceiverAccountNumber(),
            orderModel.getStatus().toString(),
            orderModel.getCreatedAt().toString()
        );
    }

}
