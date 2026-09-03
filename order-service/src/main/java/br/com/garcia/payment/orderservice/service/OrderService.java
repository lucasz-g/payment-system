package br.com.garcia.payment.orderservice.service;

import java.util.List;

import org.springframework.stereotype.Service;

import br.com.garcia.payment.orderservice.dto.OrderRequest;
import br.com.garcia.payment.orderservice.dto.OrderResponse;
import br.com.garcia.payment.orderservice.model.OrderModel;
import br.com.garcia.payment.orderservice.repository.OrderRepository;
import jakarta.transaction.Transactional;

@Service
public class OrderService {
    
    private final OrderRepository orderRepository;

    public OrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    // CRUD operations
    public List<OrderResponse> getOrders() {
        return orderRepository.findAll().stream().map(
            OrderResponse::new
        ).toList(); 
    }

    @Transactional
    public OrderResponse createOrder(OrderModel orderModel){
        orderRepository.save(orderModel);
        OrderResponse orderResponse = new OrderResponse(orderModel);
        return orderResponse; 
    }

}
