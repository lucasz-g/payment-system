# Architecture Decision Records (ADRs)

Registro das decisões de arquitetura do projeto: **o contexto** que gerou a decisão, **a decisão** em si, e **as consequências** — inclusive as ruins. O objetivo é responder daqui a seis meses à pergunta "por que isso está assim?" sem depender de memória.

Uma ADR é imutável depois de aceita. Se a decisão mudar, escreve-se uma nova que **supersede** a anterior, e a antiga tem o status atualizado para `Substituída por 000X`. O histórico de decisões erradas é tão útil quanto o das certas.

| # | Título | Status |
|---|---|---|
| [0001](0001-migracoes-de-banco-com-flyway.md) | Migrações de banco com Flyway (e não `ddl-auto: update`) | Aceita — aplicação parcial pendente |
| [0002](0002-validacao-customizada-validrecipient.md) | Validação customizada de classe: `@ValidRecipient` | Aceita |
| [0003](0003-dtos-request-e-response.md) | DTOs de entrada e saída, e a direção da conversão | Aceita |

## Template

```markdown
# ADR 000X — Título curto e afirmativo

- **Status:** Proposta | Aceita | Substituída por 000Y
- **Data:** AAAA-MM-DD
- **Contexto:** serviço afetado

## Contexto
Qual é a força em jogo? O que dói hoje? Sem propor solução ainda.

## Decisão
O que foi decidido, no presente do indicativo ("Adotar X"), e por quê.

## Consequências
O que fica melhor, o que fica pior, e o que passa a ser obrigatório manter.

## Alternativas consideradas
O que foi descartado e o motivo — a parte que mais se perde com o tempo.
```
