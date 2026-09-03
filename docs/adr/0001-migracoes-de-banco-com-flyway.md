# ADR 0001 — Migrações de banco com Flyway (e não `ddl-auto: update`)

- **Status:** Aceita — migração `V1` criada; ajustes de `pom.xml` e `application.yml` **pendentes** (ver "Como aplicar")
- **Data:** 2026-09-02
- **Contexto:** `order-service`
- **Relacionadas:** [0002 — `@ValidRecipient`](0002-validacao-customizada-validrecipient.md), [0003 — DTOs](0003-dtos-request-e-response.md)

## Contexto

O `order-service` sobe hoje com:

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: update
```

Ou seja, **o Hibernate cria e altera o schema sozinho**, inferindo tudo a partir das anotações de `OrderModel`. Funciona no primeiro dia e vira dívida no segundo, por três motivos:

1. **`update` só sabe adicionar.** Cria tabelas e colunas novas, mas nunca remove coluna, nunca renomeia, nunca muda tipo e nunca migra dado existente. Renomear `receiverEmail` → `recipientEmail` deixaria as **duas** colunas no banco: a antiga com os dados, a nova vazia.
2. **O schema não fica versionado no Git.** O estado real do banco passa a ser resultado do histórico de execuções da aplicação, não de um arquivo revisável em PR. Dois devs com bancos locais diferentes divergem em silêncio, e não existe resposta para "qual era o schema no release passado?".
3. **Não é aceitável em produção.** O app precisaria de permissão de DDL em runtime, e um deploy poderia alterar o schema sem revisão.

Como o projeto é **database-per-service** (cada serviço com seu Postgres, ver README), cada serviço precisa do próprio histórico de migrações — o que reforça versionar o schema junto do código do serviço.

## Decisão

Adotar **Flyway** como dono do schema do `order-service`, e rebaixar o Hibernate a **validador**.

### Layout

```text
order-service/src/main/resources/db/migration/
└── V1__create_order_table.sql
```

Convenção do nome: `V<versão>__<descrição>.sql` — **dois underscores**. O Flyway cria a tabela de controle `flyway_schema_history`, guarda um checksum de cada arquivo aplicado e recusa subir se um arquivo já aplicado tiver sido editado.

### Regra de ouro

**Migração aplicada é imutável.** Depois que um `V*.sql` roda em qualquer ambiente compartilhado, ele nunca mais é editado — toda mudança vira um `V2`, `V3`, …

Corolário prático: **toda mudança em `OrderModel` exige uma migração nova no mesmo commit.** Adicionou um campo? Vem `V2__add_campo.sql` junto. Se esquecer, `ddl-auto: validate` derruba o startup — que é exatamente o comportamento desejado: falha barulhenta e cedo.

## Como aplicar (pendente)

### 1. Dependências (`order-service/pom.xml`)

```xml
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-database-postgresql</artifactId>
</dependency>
```

> Desde o Flyway 10 o suporte a cada banco vive em um módulo separado — só o `flyway-core` **não** basta para Postgres. As versões vêm do `spring-boot-starter-parent`; não fixe versão à mão.

### 2. Configuração (`application.yml`)

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate   # era: update
  flyway:
    enabled: true
    locations: classpath:db/migration
```

`validate` é a peça central da decisão: na subida, o Hibernate **compara** o mapeamento das entidades com o schema real e falha o startup se divergirem. O erro aparece no boot, não em produção na primeira query.

### 3. Primeira execução com banco já existente

Se o seu Postgres local **já tem** a tabela criada pelo `ddl-auto: update`, o Flyway reclama de schema não-vazio sem histórico. Duas saídas:

1. **Recomendado em dev** — jogar o banco fora e deixar o Flyway montar do zero:
   ```bash
   docker compose down -v
   docker compose up -d
   ```
2. **Manter os dados** — marcar o estado atual como baseline: `spring.flyway.baseline-on-migrate=true` e `baseline-version=1`. Nesse caso o Flyway assume que o schema atual já corresponde à `V1` e **não** a executa. Confira se ele bate de fato: o `ddl-auto: update` não cria os `CHECK`, por exemplo.

## O que a `V1` contém

A `V1__create_order_table.sql` cria `order_model` espelhando `OrderModel`, com decisões que valem registrar:

| Item | Escolha | Porquê |
|---|---|---|
| Nome da tabela | `order_model` | É o que a naming strategy padrão do Spring gera a partir de `@Entity OrderModel` — a classe não declara `@Table`. Batizar de `orders` exigiria anotar a entidade (e uma `V2` com `RENAME`). Nunca use `order`: é palavra reservada em SQL. |
| `order_id` | `UUID` | `@GeneratedValue(strategy = GenerationType.UUID)` — o id é gerado pela aplicação, não pelo banco. Sem sequence. |
| `amount` | `NUMERIC(19,2)` | Default do Hibernate para `BigDecimal`. **Nunca** `FLOAT`/`DOUBLE` para dinheiro: binário não representa `0,10` exato e o erro acumula. |
| `status` | `VARCHAR(20)` + `CHECK` | `@Enumerated(EnumType.STRING)` grava o nome. Preferível a `ORDINAL`, que grava o índice e corrompe todo o histórico se alguém reordenar o enum. O `CHECK` impede lixo vindo de fora da aplicação. |
| `created_at` | `TIMESTAMP NOT NULL` | O campo é inicializado em Java (`= LocalDateTime.now()`), então o `NOT NULL` é seguro. |
| Destinatários | ambos nullable + `CHECK` | Um pedido precisa de email **ou** conta; o `CHECK` replica no banco a regra de `@ValidRecipient`. Ver [ADR 0002](0002-validacao-customizada-validrecipient.md) — inclusive a divergência entre "pelo menos um" e "exatamente um". |
| `ix_order_model_status` | índice | O frontend faz polling por status e a leitura de pendentes filtra por essa coluna. |

### Por que duplicar a validação no `CHECK`?

Não é redundância à toa — são **camadas com alcances diferentes**:

- A validação em Java (Bean Validation) protege **a API**: devolve `400` e uma mensagem legível. Vale só para o que entra pelo controller.
- O `CHECK` protege **o dado**. Vale para script de correção manual, seed, carga, outro serviço que um dia aponte para esse banco, e bug na aplicação. É a última linha de defesa e não tem como ser burlada.

O preço é manter as duas em sincronia — daí os comentários cruzados dentro do `.sql`.

## Consequências

**Positivas**

- Schema versionado, revisável em PR e reproduzível: `docker compose down -v` + subir o app dá exatamente o mesmo banco em qualquer máquina.
- Divergência entre entidade e schema falha no **startup**, não em runtime.
- Base pronta para testes de integração com Testcontainers (o teste sobe um Postgres limpo e o Flyway monta o schema) — item que já está no roadmap do README.
- A aplicação deixa de precisar de permissão de DDL fora da janela de migração.

**Negativas / custos**

- Toda mudança de modelo custa um arquivo SQL a mais. É intencional: torna a mudança de schema uma decisão explícita.
- Migração aplicada não pode ser editada — errou, corrige numa versão seguinte.
- É preciso escrever SQL de Postgres à mão; o Hibernate não gera mais por você.

## Alternativas consideradas

| Alternativa | Por que não |
|---|---|
| Continuar com `ddl-auto: update` | Não versiona, não remove/renomeia/migra dado, e exige DDL em runtime. |
| `ddl-auto: create-drop` | Apaga tudo a cada restart. Serve para teste automatizado, não para desenvolvimento nem produção. |
| **Liquibase** | Equivalente e igualmente suportado pelo Spring Boot. Optamos por Flyway pelo SQL puro: o arquivo de migração é o próprio DDL do Postgres, sem uma camada XML/YAML no meio — melhor para aprender o que está de fato acontecendo. Liquibase compensa quando é preciso abstrair múltiplos bancos ou gerar rollback automático. |
| Script de schema no `docker-compose` | Não versiona a evolução e não roda em produção. |
