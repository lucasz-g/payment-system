# ADR 0003 — DTOs de entrada e saída, e a direção da conversão

- **Status:** Aceita
- **Data:** 2026-09-02
- **Contexto:** `order-service`
- **Relacionadas:** [0001 — Flyway](0001-migracoes-de-banco-com-flyway.md), [0002 — `@ValidRecipient`](0002-validacao-customizada-validrecipient.md)

## Contexto

O `order-service` expõe uma API REST consumida pelo frontend Next.js e persiste pedidos com JPA. A pergunta é: o `OrderModel` (a `@Entity`) pode circular na fronteira HTTP, entrando no `@RequestBody` e saindo no corpo da resposta?

Não. Expor a entidade direto acopla **três coisas que mudam por motivos diferentes**:

- **Contrato da API** — muda quando o frontend precisa de um campo novo.
- **Schema do banco** — muda quando a persistência precisa evoluir (ver [ADR 0001](0001-migracoes-de-banco-com-flyway.md)).
- **Modelo de domínio** — muda quando a regra de negócio muda.

Com a entidade exposta, renomear uma coluna quebra o frontend, e um campo interno novo vaza para o mundo sem ninguém decidir isso. Pior: no `POST`, o Jackson preencheria **qualquer** campo que viesse no JSON — inclusive `orderId`, `status` e `createdAt`. Um cliente mandando `{"status": "APPROVED"}` criaria um pedido já aprovado, pulando todo o fluxo de pagamento. Isso tem nome: *mass assignment*.

Daí dois DTOs, ambos `record`:

- **`OrderRequest`** — o que o cliente **pode** informar: `amount`, `receiverEmail`, `receiverAccountNumber`. O que ele *não pode* decidir (`orderId`, `status`, `createdAt`) simplesmente não existe no record, e por isso é impossível de injetar.
- **`OrderResponse`** — o que a API **decide** devolver.

`record` é a escolha natural aqui: DTO é dado imutável sem identidade, exatamente o que um record é. De quebra, o Jackson (a partir do Spring Boot 3) desserializa records nativamente, sem precisar de setters nem de `@JsonCreator`.

## Decisão

### 1. `OrderModel` → `OrderResponse`: por **construtor** do DTO

```java
public OrderResponse(OrderModel orderModel) {
    this(orderModel.getAmount(), orderModel.getReceiverEmail(), ...);
}
```

Está correto, e por um motivo que vale explicitar: **a resposta é uma projeção de algo que já existe e já está completo.** O `OrderModel` que chega aqui veio do banco (ou já foi salvo), com id, status e timestamp definidos. Não há nada a "montar em etapas" — todos os valores estão disponíveis no instante da construção, que é justamente a condição em que um construtor é a ferramenta certa. O resultado é um objeto **imutável e sempre válido**: não existe um `OrderResponse` pela metade.

O bônus prático é o *method reference*, que o `OrderService` já usa:

```java
orderRepository.findAll().stream().map(OrderResponse::new).toList();
```

Isso só funciona porque existe um construtor de um argumento com o tipo do elemento do stream. Um método `toResponse()` na entidade daria `.map(OrderModel::toResponse)` — igualmente conciso, mas com a **dependência invertida**: a entidade passaria a importar o pacote `dto`, ou seja, o domínio conhecendo a camada web. A regra que orienta os dois lados é sempre a mesma:

> **A dependência aponta para dentro.** O DTO (camada externa) pode conhecer o modelo. O modelo (camada interna) não conhece o DTO.

Vale a checagem: `OrderModel` hoje tem um `import ...dto.OrderResponse` sem uso — resquício de uma tentativa nessa direção. Removê-lo mantém a regra visível.

### 2. `OrderRequest` → `OrderModel`: por **método** + setters

```java
public OrderModel toOrderModel() {
    OrderModel newOrder = new OrderModel();
    newOrder.setAmount(this.amount());
    ...
    return newOrder;
}
```

Também correto — mas o motivo é mais preciso do que "não dá por construtor". Tecnicamente *daria*: o `@AllArgsConstructor` do Lombok existe. O que sustenta a escolha são três coisas:

1. **Direção da dependência (a mesma regra de cima).** `new OrderModel(request)` exigiria um construtor em `OrderModel` recebendo `OrderRequest` — a entidade importando o DTO, domínio conhecendo a camada web. Já `request.toOrderModel()` mantém o conhecimento onde ele pode estar: no DTO.
2. **A conversão é parcial, por natureza.** O request carrega **3 dos 6 campos**. `orderId`, `status` e `createdAt` não vêm do cliente — vêm do sistema (`@GeneratedValue`, regra de negócio, relógio). Um construtor deveria receber *todos* os campos ou ter um valor pronto para cada um; setters expressam com naturalidade "preencha o que veio, o resto o sistema decide". É a diferença entre **projetar** um objeto completo (caso do response) e **montar** um objeto incompleto (caso do request).
3. **JPA exige um construtor sem argumentos.** O Hibernate instancia entidades por reflection e depois popula os campos — é por isso que `@NoArgsConstructor` está lá e por isso a entidade é naturalmente mutável, ao contrário do DTO. O `toOrderModel()` só segue o mesmo estilo que a persistência já impõe.

Em resumo: **construtor para o que já está pronto e imutável (response); método + setters para o que é montado em etapas e ainda vai receber dados do sistema (request → entidade).**

### 3. Onde a conversão acontece

Hoje o `OrderController` chama `request.toOrderModel()` e passa um `OrderModel` para o service. Funciona, mas coloca a tradução no controller e faz o service receber um tipo de persistência.

A alternativa é o service receber o `OrderRequest` e devolver o `OrderResponse`, deixando o controller só com o que é HTTP (status code, headers, roteamento). O ganho concreto é que **as decisões do sistema — `status = PENDING`, publicar o evento `OrderCreated` no RabbitMQ — passam a ter um lugar óbvio**, dentro do service, em vez de ficarem espalhadas entre controller e DTO. Fica registrado como direção preferida quando a publicação de eventos entrar.

## Revisão dos DTOs — achados

Achados da leitura de `OrderRequest`, `OrderResponse`, `OrderController` e `OrderService`.

### Resolvidos em 2026-09-02

| Onde | Era | Ficou |
|---|---|---|
| `OrderController.createOrder` | Faltava `@Valid` no `@RequestBody` — `@ValidRecipient`, `@NotNull` e `@Positive` não rodavam, a validação inteira estava inerte. | `@Valid @RequestBody OrderRequest request`. Violação agora vira `MethodArgumentNotValidException` → **400**. |
| `OrderModel.status` | Nunca era preenchido no `toOrderModel()`, e a coluna é `NOT NULL` → insert falhava. | Inicializado no campo: `private OrderStatus status = OrderStatus.PENDING;`. |
| `OrderService.createOrder` | O `OrderResponse` era montado **antes** do `save()`. | `save()` primeiro, mapeamento depois. |

Sobre o `PENDING` no inicializador de campo: funciona e mantém a entidade sempre válida por construção, inclusive para escritas que não venham do `toOrderModel()`. Dois pontos que valem saber:

- **`@AllArgsConstructor` ignora o inicializador.** Quem construir por ele precisa passar o status explicitamente. Como o caminho real usa `new OrderModel()` + setters, não há problema hoje.
- **O Hibernate também ignora**, e isso é o comportamento desejado: ele instancia pelo construtor sem argumentos (rodando o inicializador) e em seguida sobrescreve os campos com o que veio do banco. Um pedido `APPROVED` carregado do banco continua `APPROVED`.
- Quando o `payment-service` entrar no fluxo, a transição de estado (`PENDING → APPROVED/REJECTED`) é regra de negócio e pertence ao service, não ao inicializador — que fica sendo só o valor inicial.

### Em aberto

| # | Onde | Observação |
|---|---|---|
| 1 | `OrderResponse` | **Não expõe `orderId`.** O README prevê `GET /orders/{id}` com polling do frontend — sem o id na resposta do `POST`, o cliente não tem como consultar o próprio pedido. É o próximo passo mais urgente para o fluxo funcionar ponta a ponta. |
| 2 | `OrderResponse` | `status` e `createdAt` viram `String` via `.toString()`. O risco de NPE sumiu (ambos agora têm valor sempre), mas `LocalDateTime.toString()` fixa o formato de serialização no Java; devolver `LocalDateTime`/`Instant` e deixar o Jackson formatar em ISO-8601 dá mais controle ao frontend. |
| 3 | tratamento de erro | Com `@Valid` ativo, o 400 já acontece — mas com o corpo padrão do Spring. Um `@RestControllerAdvice` com `@ExceptionHandler(MethodArgumentNotValidException.class)` daria uma resposta estável (lista de campo + mensagem) para o frontend consumir. |
| 4 | `OrderModel` | `@Data` numa `@Entity` gera `equals`/`hashCode` sobre **todos** os campos, incluindo o id — quebra identidade de objetos ainda não persistidos e em coleções. `@Getter @Setter` (+ equals por id, quando precisar) é o caminho usual. |
| 5 | `OrderModel` | As constraints de Bean Validation (`@NotNull`, `@Positive`, `@Email`) estão duplicadas na entidade. Não é errado — o Hibernate as aplica no `pre-persist`, funcionando como rede de segurança para escritas que não passam pelo controller —, mas é duplicação a manter em sincronia com o DTO **e** com os `CHECK` do banco. Vale ser decisão consciente, não acidente. |
| 6 | imports | `OrderService` importa `OrderRequest` sem usar; `OrderModel` importa `OrderResponse` sem usar (ver seção 1 — resquício da conversão na direção errada). |

## Consequências

**Positivas**

- Contrato da API desacoplado do schema: migração de banco não quebra o frontend, e vice-versa.
- *Mass assignment* impossível por construção — o cliente não consegue definir `status` nem `orderId` porque esses campos não existem no `OrderRequest`.
- Validação de entrada concentrada no DTO, junto do contrato que ela descreve.
- Records imutáveis: o objeto que sai do controller é o mesmo que entrou, sem mutação silenciosa no meio.

**Negativas / custos**

- Duplicação de campos entre DTO e entidade, e a tradução manual entre eles. É o preço do desacoplamento — e cresce com o número de campos.
- Cada campo novo do domínio exige decidir conscientemente se entra no request, no response, em ambos ou em nenhum. Isso é uma feature, não um bug, mas é trabalho.
- Duas fontes de verdade para as mesmas regras de validação (DTO e entidade) — ver #7.

## Alternativas consideradas

| Alternativa | Por que não |
|---|---|
| Expor `OrderModel` direto no controller | Acopla contrato, schema e domínio; abre *mass assignment* em `status`/`orderId`; vaza campos internos. |
| MapStruct (mapper gerado em tempo de compilação) | A escolha certa quando os mapeamentos se multiplicam — elimina o boilerplate com código gerado, sem reflection. Rejeitado agora por serem só dois mapeamentos triviais: a dependência e o processador de anotações custariam mais do que economizam. Reavaliar quando `payment-service` e `email-service` tiverem os próprios DTOs. |
| Um DTO único para entrada e saída | Volta ao problema do *mass assignment*: os campos que o servidor decide teriam de existir no objeto de entrada. Entrada e saída têm formatos diferentes porque têm **autores** diferentes. |
| Classe comum + Lombok `@Builder` no lugar de `record` | Perde a imutabilidade garantida e a desserialização nativa do Jackson, sem ganho relevante para 3–5 campos. |
