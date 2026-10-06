-- =====================================================================
-- V1: tabela base de pedidos do order-service.
--
-- Espelha br.com.garcia.payment.orderservice.model.OrderModel, que declara
-- @Table(name = "orders"). Nao dependemos da naming strategy padrao do
-- Spring Boot aqui: o nome da tabela e explicito nos dois lados.
--
-- O schema e responsabilidade exclusiva do Flyway. O Hibernate roda com
-- ddl-auto: validate e apenas confere que entidade e tabela batem --
-- por isso os tipos abaixo precisam casar com as anotacoes @Column.
--
-- Contexto e decisoes: docs/adr/0001-migracoes-de-banco-com-flyway.md
-- =====================================================================

CREATE TABLE orders (
    order_id                UUID           NOT NULL,
    payer_name              VARCHAR(100)   NOT NULL,
    amount                  NUMERIC(19, 2) NOT NULL,
    receiver_email          VARCHAR(255),
    receiver_account_number VARCHAR(50),
    description             VARCHAR(100),
    status                  VARCHAR(20)    NOT NULL,
    created_at              TIMESTAMP      NOT NULL,

    CONSTRAINT pk_orders PRIMARY KEY (order_id),

    -- Espelha @Positive em amount.
    CONSTRAINT ck_orders_amount_positive CHECK (amount > 0),

    -- Espelha @ValidRecipient: o pedido precisa de PELO MENOS um destinatario.
    -- Regra de negocio decidida (2026-09-02): email OU conta OU os dois.
    -- Mesma semantica do RecipientValidator (hasEmail || hasAccountNumber).
    -- Ver docs/adr/0002-validacao-customizada-validrecipient.md
    CONSTRAINT ck_orders_has_recipient CHECK (
        receiver_email IS NOT NULL OR receiver_account_number IS NOT NULL
    ),

    -- OrderStatus persistido como texto (@Enumerated(EnumType.STRING)).
    CONSTRAINT ck_orders_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED'))
);

-- O frontend faz polling por status e o consumo de eventos filtra pendentes.
CREATE INDEX ix_orders_status ON orders (status);
