# ADR 0002 — Validação customizada de classe: `@ValidRecipient`

- **Status:** Aceita
- **Data:** 2026-09-02
- **Contexto:** `order-service`
- **Relacionadas:** [0001 — Flyway](0001-migracoes-de-banco-com-flyway.md), [0003 — DTOs](0003-dtos-request-e-response.md)

## Contexto

Um pedido tem **um destinatário**, informado de duas formas alternativas:

- `receiverEmail` — quando o pagamento vai para um email;
- `receiverAccountNumber` — quando vai para uma conta.

Nenhum dos dois é obrigatório **isoladamente** — a obrigatoriedade é da *combinação*. E é exatamente isso que o Bean Validation padrão não consegue expressar: `@NotNull`, `@Email`, `@Size` são anotações **de campo**, e um campo não enxerga o vizinho. `@NotNull` nos dois tornaria ambos obrigatórios; em nenhum, permitiria um pedido órfão, sem destino.

Onde colocar essa regra "entre campos", então?

| Lugar | Problema |
|---|---|
| `if` no controller | Mistura validação com roteamento HTTP, não é reaproveitável e não entra no relatório de violações do Bean Validation. |
| `if` no service | Regra de *formato de entrada* vazando para a camada de negócio, que passa a ter que devolver erro de payload. |
| Compact constructor do `record` | Roda de fato, mas só sabe lançar exceção — `IllegalArgumentException` vira **500**, não **400**, e a mensagem não chega estruturada ao cliente. |
| **Constraint customizada de classe** | ✅ Regra declarativa, no mesmo mecanismo das demais, agregada na mesma resposta de erro. |

## Decisão

Criar uma **constraint de classe** (*class-level constraint*) do Jakarta Bean Validation.

### A anotação

```java
@Target({ ElementType.TYPE })                        // (1) nível de classe
@Retention(RetentionPolicy.RUNTIME)                  // (2) visível por reflection
@Constraint(validatedBy = RecipientValidator.class)  // (3) quem executa a lógica
public @interface ValidRecipient {
    String message() default "Informe exatamente um destinatário: conta OU email";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
```

Linha a linha, porque cada uma resolve um problema específico:

1. **`@Target(ElementType.TYPE)`** — é o que torna a constraint *de classe*. `TYPE` faz a anotação valer sobre a classe/record inteiro, não sobre um campo. Consequência direta: o validator recebe o **objeto todo** e pode comparar campos entre si. Se fosse `FIELD`, ele receberia uma `String` isolada e o problema voltaria ao ponto de partida.
2. **`@Retention(RUNTIME)`** — anotações Java são descartadas depois da compilação por padrão (`CLASS`). O Hibernate Validator descobre constraints **por reflection, em runtime**; sem `RUNTIME` a anotação simplesmente não existe quando importa, e a validação é ignorada em silêncio. É o erro clássico da primeira anotação customizada.
3. **`@Constraint(validatedBy = ...)`** — liga o *contrato* (a anotação) à *implementação* (o validator). A separação existe para que a mesma anotação possa ter várias implementações, uma por tipo validado.

Os três membros do corpo **não são opcionais**: a spec exige `message()`, `groups()` e `payload()` em toda constraint, e a ausência de qualquer um faz a inicialização do validator estourar.

- `message()` — texto (ou chave `{...}` de bundle i18n) devolvido na violação. O default pode ser sobrescrito no uso: `@ValidRecipient(message = "...")`.
- `groups()` — permite validar em cenários distintos (ex.: `OnCreate` vs `OnUpdate`) sem duplicar o DTO.
- `payload()` — metadado extra para quem consome a violação (ex.: severidade `Warn`/`Error`). Raramente usado, mas obrigatório.

### O validator

```java
public class RecipientValidator implements ConstraintValidator<ValidRecipient, OrderRequest> {
    @Override
    public boolean isValid(OrderRequest request, ConstraintValidatorContext context) { ... }
}
```

Os dois parâmetros genéricos são `<A extends Annotation, T>`: **qual anotação** ele implementa e **qual tipo** ele valida. Amarrar em `OrderRequest` (e não em `Object`) dá checagem em tempo de compilação e acesso direto aos acessores do record — em troca, a constraint fica específica deste DTO.

Detalhes de implementação que valem registrar:

- **`isBlank()` além de `!= null`.** JSON manda `""` com frequência (input vazio no frontend). String vazia é um destinatário tão inútil quanto `null`, então os dois casos são tratados igual.
- **`isValid` nunca lança exceção — retorna `false`.** Exceção dentro de um validator vira `ValidationException` → **500**, não `400`.
- **O validator não é um bean gerenciado.** É instanciado pelo Hibernate Validator. No Spring Boot ele *pode* receber injeção (o `LocalValidatorFactoryBean` usa o container como factory) — útil se um dia a regra precisar consultar o banco —, mas hoje não depende de nada.
- **`initialize(ValidRecipient ann)`** não foi sobrescrito porque a anotação não tem atributo de configuração. Se ela ganhasse, por exemplo, um `boolean allowBoth()`, seria ali que o valor seria lido e guardado.

### O gatilho: `@Valid` no controller

Ponto que costuma pegar: **anotar o DTO não valida nada sozinho.** A validação do corpo da requisição só dispara quando o parâmetro do controller está marcado:

```java
@PostMapping("/create")
public ResponseEntity<OrderResponse> createOrder(@Valid @RequestBody OrderRequest request) { ... }
```

Sem `@Valid`, o Spring desserializa o JSON e entrega o objeto direto ao método — `@ValidRecipient`, `@NotNull` e `@Positive` ficam todos inertes, sem nenhum aviso. Vale conferir isso no `OrderController` atual.

Quando dispara, o Spring lança `MethodArgumentNotValidException`, traduzida em **400 Bad Request**. Para controlar o corpo da resposta (devolver um JSON com a lista de campos e mensagens, por exemplo), o caminho é um `@RestControllerAdvice` com `@ExceptionHandler(MethodArgumentNotValidException.class)` — ainda não existe no projeto e é um próximo passo natural.

## A cardinalidade da regra: "pelo menos um" (decidido em 2026-09-02)

O nome da anotação e a redação original da mensagem sugeriam *exclusividade* ("exatamente um destinatário"), mas a regra de negócio é **inclusiva**: um pedido é válido com email, com conta, **ou com os dois**. É o `||` que o `RecipientValidator` já implementa:

```java
return hasEmail || hasAccountNumber;
```

A leitura de domínio que sustenta isso: os dois campos não são formas concorrentes de identificar *o mesmo* destino, e sim **informações complementares** sobre ele. A conta diz para onde o dinheiro vai; o email diz para onde a notificação vai. Quando o cliente tem os dois, guardar os dois é mais informação, não ambiguidade — e o `email-service` (ver README) agradece.

Consequências dessa escolha, registradas explicitamente:

- **O `CHECK` da `V1`** usa `receiver_email IS NOT NULL OR receiver_account_number IS NOT NULL`, alinhado ao validator. Ver [ADR 0001](0001-migracoes-de-banco-com-flyway.md).
- **Nenhum campo isolado é `NOT NULL`** no banco, nem `@NotNull` no DTO — a obrigatoriedade continua sendo da combinação, que é exatamente o motivo de a constraint ser de classe.
- **A mensagem default ainda diz "exatamente um"** e passou a estar errada em relação à regra decidida. Um cliente que mande os dois campos nunca verá esse texto (o payload é válido), mas quem não mandar nenhum recebe uma instrução que não corresponde ao contrato. Ajustar para algo como *"Informe ao menos um destinatário: email ou número da conta"* — pendência de uma linha.
- **Se algum dia a regra virar exclusiva**, o caminho é `return hasEmail ^ hasAccountNumber;` no validator e uma `V2` trocando o `CHECK` por `(receiver_email IS NOT NULL) <> (receiver_account_number IS NOT NULL)`. Nesse cenário, atenção aos dados já gravados com os dois campos: o `ALTER TABLE ... ADD CONSTRAINT` falha se qualquer linha existente violar a nova regra, então a migração precisaria limpar o dado antes.

## Consequências

**Positivas**

- A regra fica declarativa e num lugar só: ler `@ValidRecipient` no topo do `OrderRequest` já conta a história.
- Entra no mesmo relatório de violações das outras constraints — o cliente recebe **todos** os erros do payload de uma vez, não um a cada round-trip.
- Testável isoladamente, sem subir contexto Spring: instanciar o validator direto, ou usar `Validation.buildDefaultValidatorFactory().getValidator().validate(request)`.
- Reaproveitável: qualquer outro DTO com o mesmo par de campos ganha a regra com uma linha.

**Negativas / custos**

- Três arquivos (anotação, validator, uso) para uma regra de uma linha — só compensa quando a regra é de fato do domínio e tende a se repetir.
- Muita cerimônia obrigatória (`groups`, `payload`, `RUNTIME`) cujo esquecimento falha de forma silenciosa ou obscura.
- O validator conhece `OrderRequest` concretamente: acopla o pacote `validation` ao pacote `dto`. Aceitável aqui; a alternativa genérica seria ler os campos por reflection a partir de nomes passados na anotação (como faz o `@FieldMatch` do Hibernate Validator) — bem mais frágil e sem checagem em compilação.
- A violação é **de classe**, não de campo: a resposta padrão de erro aponta para o objeto inteiro, sem dizer "o problema está em `receiverEmail`". Se isso incomodar, dá para direcionar dentro do validator:

```java
context.disableDefaultConstraintViolation();
context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
       .addPropertyNode("receiverEmail")
       .addConstraintViolation();
```

## Alternativas consideradas

| Alternativa | Por que não |
|---|---|
| Validar no service com `if` | Regra de payload vazando para a camada de negócio; sem integração com o relatório de violações. |
| Compact constructor do record | Roda cedo demais (na desserialização) e só sabe lançar exceção → 500 em vez de 400. |
| `@AssertTrue` num método do record | Funciona (`@AssertTrue boolean isRecipientValid()`) e é a opção mais barata. Rejeitado por não ser reaproveitável em outros DTOs e por poluir o record com um método que não é dado. |
| Dois DTOs distintos (`OrderByEmailRequest` / `OrderByAccountRequest`) + polimorfismo Jackson | Elimina o estado inválido *por construção* — a modelagem mais correta em teoria. Rejeitado por custo: exigiria `@JsonSubTypes`, um discriminador no payload e mudança no contrato do frontend, para um domínio com só duas variantes. Vale reconsiderar se aparecer uma terceira forma de destinatário. |
