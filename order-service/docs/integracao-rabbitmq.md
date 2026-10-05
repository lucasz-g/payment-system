# Integração com RabbitMQ

Passo a passo da integração do `order-service` com o RabbitMQ: subir o broker, conectar a
aplicação, declarar a fila e publicar mensagens. O objetivo aqui é o **como**; as decisões
de arquitetura e seus porquês ficam nas [ADRs](adr/README.md).

## Visão geral

Quando um pedido é criado, o `OrderService` grava no Postgres e publica uma mensagem em
`order.queue`. Quem consome essa fila (o futuro serviço de notificação) fica desacoplado:
o `order-service` responde ao cliente sem esperar ninguém.

```text
POST /orders ──> OrderService ──> Postgres (orders)
                      │
                      └── RabbitTemplate ──> exchange padrão ("") ──> order.queue
```

---

## 1. Subir o RabbitMQ no `docker-compose.yml`

```yaml
rabbitmq:
  image: rabbitmq:4-management
  container_name: rabbitmq
  restart: always
  environment:
    - RABBITMQ_DEFAULT_USER=admin
    - RABBITMQ_DEFAULT_PASS=SuperSecurePass123
  ports:
    - "5672:5672"    # AMQP — é por aqui que a aplicação fala
    - "15672:15672"  # HTTP — Management UI, para humanos
  volumes:
    - rabbitmq_data:/var/lib/rabbitmq
  healthcheck:
    test: ["CMD", "rabbitmq-diagnostics", "check_port_connectivity"]
    interval: 10s
    timeout: 5s
    retries: 3

volumes:
  rabbitmq_data:
```

**As duas portas confundem no começo:** a aplicação conecta na **5672** (protocolo AMQP);
o navegador abre a **15672** (interface web). Tag `-management` é o que traz essa UI —
a imagem `rabbitmq:4` pura sobe só o broker, sem interface.

Subir e conferir:

```bash
docker compose up -d rabbitmq
```

UI em <http://localhost:15672> — login `admin` / `SuperSecurePass123`.

> O volume `rabbitmq_data` faz filas e mensagens sobreviverem a `docker compose down`.
> Para zerar tudo (útil quando uma fila foi declarada errada): `docker compose down -v`.

---

## 2. Dependência no `order-service/pom.xml`

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-amqp</artifactId>
</dependency>
```

Sem versão — vem do `spring-boot-starter-parent`. Esse starter já traz a autoconfiguração
que cria o `ConnectionFactory`, o `RabbitTemplate` e o `RabbitAdmin` prontos para injetar.

Para testes existe o par `spring-boot-starter-amqp-test` (escopo `test`), já declarado no
projeto.

---

## 3. Conexão no `application.yml`

```yaml
spring:
  rabbitmq:
    host: localhost
    port: 5672
    username: admin
    password: SuperSecurePass123
```

Precisa bater com as credenciais do compose. A porta é a **5672**, não a da UI.

---

## 4. Declarar a fila: `RabbitMQConfig`

Não é preciso criar a fila pela Management UI. Basta expor um bean `Queue`: o `RabbitAdmin`
que o starter registra declara no broker toda `Queue`, `Exchange` e `Binding` que encontrar
no contexto.

`config/RabbitMQConfig.java`:

```java
@Configuration
public class RabbitMQConfig {

    public static final String QUEUE_NAME = "order.queue";

    @Bean
    Queue orderQueue() {
        // durable = true: a fila sobrevive a restart do broker
        return new Queue(QUEUE_NAME, true);
    }
}
```

Dois pontos que economizam tempo:

- **Declarar é idempotente.** Subir a aplicação com a fila já existente não dá erro.
- **Desde que os atributos batam.** Se a fila já existir com configuração diferente
  (criada pela UI como não-durável, por exemplo), o broker responde `PRECONDITION_FAILED`
  e fecha o canal. Por isso a fila é declarada **só no código** — versionado e
  reproduzível. Se acontecer, `docker compose down -v` resolve em dev.

O nome vive numa constante para que produtor e futuro consumidor não dependam de string
solta digitada duas vezes.

### Quando a fila é realmente criada

**Não é na subida da aplicação.** O `RabbitAdmin` implementa apenas `InitializingBean` —
não `SmartLifecycle`, nem `ApplicationListener`. No `afterPropertiesSet()` ele só registra um
`ConnectionListener`: as declarações acontecem **quando a primeira conexão AMQP é aberta**.

E a `CachingConnectionFactory` é preguiçosa — só conecta quando alguém precisa. Num serviço
que ainda só publica, sem nenhum `@RabbitListener`, isso significa que **a fila só aparece na
UI depois da primeira mensagem publicada**. Antes disso o app sobe limpo, sem erro e sem uma
única linha de log sobre AMQP — e parece que a configuração não funcionou.

Para forçar a declaração na subida, abra a conexão de propósito:

```java
@Bean
ApplicationRunner declararFilas(AmqpAdmin amqpAdmin) {
    return args -> amqpAdmin.initialize();
}
```

Quando o consumidor existir isso deixa de ser necessário: o listener container do
`@RabbitListener` abre conexão no startup e as filas passam a ser declaradas sozinhas.

---

## 5. Publicar: `RabbitTemplate`

`RabbitTemplate` é injetado direto no `OrderService` — o starter já o registra.

```java
@Transactional
public OrderResponse createOrder(OrderModel orderModel) {
    orderRepository.save(orderModel);
    OrderResponse orderResponse = new OrderResponse(orderModel);
    sendMessageToQueue(orderResponse.toString());
    return orderResponse;
}

public void sendMessageToQueue(String message) {
    rabbitTemplate.convertAndSend(RabbitMQConfig.QUEUE_NAME, message);
}
```

**Como essa sobrecarga de um argumento funciona** — vale entender, porque não é óbvio:

```java
convertAndSend("order.queue", message)
// equivale a:
convertAndSend("", "order.queue", message)
//              ↑ exchange padrão   ↑ routing key
```

Não se está enviando "para a fila", e sim para o **exchange padrão** usando o nome da fila
como routing key. O exchange padrão tem a regra especial de entregar à fila de nome igual à
routing key — então funciona, desde que a fila exista.

> **A pegadinha nº 1 do RabbitMQ:** se a fila **não** existir, a mensagem é descartada em
> silêncio. Sem exceção, sem log, sem erro. O sintoma é "publiquei e não chegou nada".
> É justamente por isso que o passo 4 não é opcional.

Testar: `POST /orders` e conferir em <http://localhost:15672> → aba **Queues** →
`order.queue`, coluna *Ready*.

---

## 6. Pendente: converter para JSON com Jackson

**Ainda não aplicado no código.** Hoje o service publica `orderResponse.toString()`, o que
gera algo como `OrderResponse[orderId=..., amount=...]` — legível na UI, mas impossível de
um consumidor desserializar de volta em objeto.

O caminho é registrar um `MessageConverter` Jackson. O `RabbitTemplate` passa a serializar
o objeto em JSON automaticamente e a preencher o `content_type` da mensagem:

```java
@Bean
MessageConverter jsonMessageConverter() {
    return new JacksonJsonMessageConverter();
}
```

> **Atenção à versão.** O Spring Boot 4 usa **Jackson 3** (`tools.jackson`), e o Spring AMQP 4
> acompanhou: a classe passou a ser `JacksonJsonMessageConverter`. O
> `Jackson2JsonMessageConverter` de todo tutorial escrito para o Boot 3 ainda existe no jar,
> mas está depreciado e amarrado ao Jackson 2. Neste projeto use a versão sem o `2`.

Com o bean no contexto, o envio passa a receber o objeto direto:

```java
rabbitTemplate.convertAndSend(RabbitMQConfig.QUEUE_NAME, orderResponse);
```

E o consumidor recebe o tipo já pronto:

```java
@RabbitListener(queues = RabbitMQConfig.QUEUE_NAME)
public void onOrder(OrderResponse order) { ... }
```

---

## Ponto de atenção conhecido

A publicação acontece **dentro** do `@Transactional` do `createOrder`. Se o commit no
Postgres falhar depois do envio, a mensagem já saiu e não volta — é o problema clássico de
*dual-write*. Não bloqueia o aprendizado, mas fica registrado: as saídas usuais são o
padrão **outbox** ou publicar em `@TransactionalEventListener(phase = AFTER_COMMIT)`.
