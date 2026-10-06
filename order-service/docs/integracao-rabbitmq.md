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

## 6. Serializar em JSON: o `MessageConverter` como bean gerenciado

**Aplicado em 2026-10-05.** Antes disso o service publicava `orderResponse.toString()`, o
que gerava algo como `OrderResponse[orderId=..., amount=...]` — legível na UI, mas impossível
de um consumidor desserializar de volta em objeto.

### A abordagem: um único bean, e o Spring liga o resto

Em `RabbitConfig` existe apenas isto:

```java
@Bean MessageConverter jsonMessageConverter() {
    return new JacksonJsonMessageConverter();
}
```

Nada além disso. Em nenhum lugar do projeto esse converter é passado para o
`RabbitTemplate` na mão — e é exatamente esse o ponto que costuma confundir.

**O mecanismo.** O `RabbitTemplate` não é criado por nós: quem o cria é a autoconfiguração
do `spring-boot-starter-amqp`, através de um `RabbitTemplateConfigurer`. E esse configurer
declara uma dependência opcional por `ObjectProvider<MessageConverter>` — ou seja, ele
*procura no contexto* se existe algum bean do tipo `MessageConverter`. Se existir exatamente
um, ele o aplica no template; se não existir nenhum, o template fica com o
`SimpleMessageConverter` padrão (que serializa String/byte[] e cai em serialização Java
binária para qualquer outro objeto).

```text
@Bean MessageConverter  ──(está no contexto)──>  RabbitTemplateConfigurer
                                                        │
                                                        └──> RabbitTemplate já configurado
                                                             e injetado onde você pedir
```

É o padrão de extensão que o Spring Boot usa em quase toda autoconfiguração: **você não
configura o componente, você publica um bean e a autoconfiguração o encontra.** Declarar o
bean é a configuração.

Duas consequências práticas disso:

- **Vale para todos os `RabbitTemplate` do contexto**, não só para um ponto de envio. Não
  existe risco de um publisher serializar em JSON e outro esquecer.
- **Se houver mais de um `MessageConverter` no contexto**, o `getIfUnique()` do
  `ObjectProvider` devolve `null` e **nenhum** é aplicado — silenciosamente, de volta ao
  comportamento padrão. Dois converters é pior que zero. Se um dia precisar de mais de um,
  marque um deles com `@Primary`.

### O que o converter faz na prática

Com ele no contexto, o `convertAndSend` passa a receber o **objeto de domínio direto** —
a serialização deixa de ser responsabilidade do código de aplicação:

```java
rabbitTemplate.convertAndSend(
        RabbitConfig.EXCHANGE,
        RabbitConfig.ORDER_CREATED_KEY,
        orderCreated);          // o objeto, não uma String
```

Além do corpo em JSON, o converter preenche os headers da mensagem: `content_type:
application/json` e o `__TypeId__` com o nome da classe de origem — é esse header que
permite ao consumidor desserializar no tipo certo sem configuração extra:

```java
@RabbitListener(queues = RabbitConfig.ORDER_CREATED_QUEUE)
public void onOrderCreated(OrderCreated event) { ... }
```

> **Atenção à versão — custou uma sessão de debug.** O Spring Boot 4 migrou para
> **Jackson 3** (pacote `tools.jackson`), e o Spring AMQP 4 acompanhou: a classe passou a
> ser `JacksonJsonMessageConverter`, **sem o `2`**. O `Jackson2JsonMessageConverter` de todo
> tutorial escrito para o Boot 3 ainda existe no `spring-amqp-4.1.1.jar`, mas é a versão
> legada, amarrada ao Jackson 2 (`com.fasterxml.jackson`) — que o Boot 4 não traz mais no
> classpath. Usá-lo compila normalmente e **quebra só na subida**, com um
> `NoClassDefFoundError: com/fasterxml/jackson/databind/json/JsonMapper` enterrado sob umas
> cinco camadas de `BeanCreationException`. O sintoma não aponta para a causa.

### Não serialize na mão antes de enviar

O erro natural de quem vem do `toString()` é continuar convertendo o objeto antes do envio:

```java
// ERRADO, com um MessageConverter JSON registrado
String json = new ObjectMapper().writeValueAsString(orderCreated);
rabbitTemplate.convertAndSend(RabbitConfig.ORDER_CREATED_QUEUE, json);
```

O converter recebe uma `String` e faz o trabalho dele: serializa **essa String** como JSON.
O resultado é um JSON escapado dentro de outro JSON, que o consumidor precisa desserializar
duas vezes — e o `__TypeId__` aponta para `java.lang.String`:

```text
objeto ──(mapper manual)──> "{\"orderId\":\"abc\"}"  ──(converter)──> "\"{\\\"orderId\\\":...\""
```

Com o bean registrado, `ObjectMapper` manual não tem lugar no publisher. (E, de forma geral,
nunca `new ObjectMapper()` a cada chamada: é caro de construir e thread-safe por design —
se precisar de um, injete o bean que o Boot já configura.)

> **Pendência conhecida (2026-10-05):** o `OrderEventPublisher` ainda faz exatamente o que
> está descrito acima como errado — serializa com um `ObjectMapper` próprio e publica a
> String resultante, além de enviar pela exchange padrão em vez da `payments.exchange`
> declarada no `RabbitConfig`. Corrigir quando o consumidor for implementado.

---

## Ponto de atenção conhecido

A publicação acontece **dentro** do `@Transactional` do `createOrder`. Se o commit no
Postgres falhar depois do envio, a mensagem já saiu e não volta — é o problema clássico de
*dual-write*. Não bloqueia o aprendizado, mas fica registrado: as saídas usuais são o
padrão **outbox** ou publicar em `@TransactionalEventListener(phase = AFTER_COMMIT)`.
