package br.com.garcia.payment.orderservice.service;

import java.util.List;

import org.springframework.stereotype.Service;

import br.com.garcia.payment.orderservice.dto.OrderResponse;
import br.com.garcia.payment.orderservice.events.OrderCreated;
import br.com.garcia.payment.orderservice.events.OrderEventPublisher;
import br.com.garcia.payment.orderservice.model.OrderModel;
import br.com.garcia.payment.orderservice.repository.OrderRepository;
import jakarta.transaction.Transactional;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderEventPublisher orderEventPublisher; 

    public OrderService(OrderRepository orderRepository, OrderEventPublisher orderEventPublisher) {
        this.orderRepository = orderRepository;
        this.orderEventPublisher = orderEventPublisher; 
    }

    // CRUD operations
    public List<OrderResponse> getOrders() {
        return orderRepository.findAll().stream().map(
                OrderResponse::new).toList();
    }

    @Transactional
    public OrderResponse createOrder(OrderModel orderModel) {
        // Salva no Banco. OrderStatus.PENDING;
        orderRepository.save(orderModel);
        // DTO para resposta HTTP
        OrderResponse orderResponse = new OrderResponse(orderModel);
        // Contrato para publicar evento. Transforma de model para contrato
        OrderCreated orderCreated = new OrderCreated(orderModel);
        // Publica evento na fila
        orderEventPublisher.sendMessageToQueue(orderCreated);
        // Retorna DTO
        return orderResponse;
    }

}