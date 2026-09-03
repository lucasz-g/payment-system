package br.com.garcia.payment.orderservice.dto;

import java.math.BigDecimal;

import br.com.garcia.payment.orderservice.model.OrderModel;
import br.com.garcia.payment.orderservice.validation.ValidRecipient;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

// Anotação customizada que valida se o email ou número da conta existem.
@ValidRecipient
public record OrderRequest(
        @NotNull @Positive BigDecimal amount,
        @Email String receiverEmail,
        String receiverAccountNumber
) {

        public OrderModel toOrderModel(){
                OrderModel newOrder = new OrderModel(); 
                newOrder.setAmount(this.amount());
                newOrder.setReceiverEmail(this.receiverEmail());
                newOrder.setReceiverAccountNumber(this.receiverAccountNumber());

                return newOrder; 
        }
}
