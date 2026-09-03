package br.com.garcia.payment.orderservice.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import br.com.garcia.payment.orderservice.dto.OrderResponse;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "orders")
@Data
@AllArgsConstructor
@NoArgsConstructor
public class OrderModel {
    
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID orderId;
    
    @NotNull @Positive 
    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount; 
    
    @Email
    @Column(name = "receiver_email", nullable = true)
    private String receiverEmail; // nullable
    
    @Column(name = "receiver_account_number", nullable = true, length = 50)
    private String receiverAccountNumber; // nullable

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OrderStatus status = OrderStatus.PENDING;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now(); 

}