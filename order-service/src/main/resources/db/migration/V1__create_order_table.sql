-- =====================================================================
-- V1: tabela base de pedidos do order-service.
--
-- Espelha br.com.garcia.payment.orderservice.model.OrderModel.
-- Nome da tabela = "order_model": e a naming strategy padrao do Spring Boot
-- (CamelCase -> snake_case) aplicada ao nome da @Entity, ja que a classe
-- nao declara @Table. Se um dia voce anotar a entidade com
-- @Table(name = "orders"), crie uma V2 com ALTER TABLE ... RENAME TO.
--
-- Contexto e decisoes: docs/adr/0001-migracoes-de-banco-com-flyway.md
-- =====================================================================

CREATE TABLE order_model (
    order_id                UUID           NOT NULL,
    amount                  NUMERIC(19, 2) NOT NULL,
    receiver_email          VARCHAR(255),
    receiver_account_number VARCHAR(50),
    status                  VARCHAR(20)    NOT NULL,
    created_at              TIMESTAMP      NOT NULL,

    CONSTRAINT pk_order_model PRIMARY KEY (order_id),

    -- Espelha @Positive em amount.
    CONSTRAINT ck_order_amount_positive CHECK (amount > 0),

    -- Espelha @ValidRecipient: o pedido precisa de PELO MENOS um destinatario.
    -- Regra de negocio decidida (2026-09-02): email OU conta OU os dois.
    -- Mesma semantica do RecipientValidator (hasEmail || hasAccountNumber).
    -- Ver docs/adr/0002-validacao-customizada-validrecipient.md
    CONSTRAINT ck_order_has_recipient CHECK (
        receiver_email IS NOT NULL OR receiver_account_number IS NOT NULL
    ),

    -- OrderStatus persistido como texto (@Enumerated(EnumType.STRING)).
    CONSTRAINT ck_order_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED'))
);

-- O frontend faz polling por status e o consumo de eventos filtra pendentes.
CREATE INDEX ix_order_model_status ON order_model (status);
