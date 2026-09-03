package br.com.garcia.payment.orderservice.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;


import br.com.garcia.payment.orderservice.model.OrderModel;

public interface OrderRepository extends JpaRepository<OrderModel, UUID> {
    
}
