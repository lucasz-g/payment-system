package br.com.garcia.payment.orderservice.validation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * ValidRecipient
 */

// Anotação customizada
@Target({ ElementType.TYPE }) // Anotação nível de classe
@Retention(RetentionPolicy.RUNTIME) // Anotação disponível em tempo de execução
@Constraint(validatedBy = RecipientValidator.class) // Classe que implementa a lógica de validação
public @interface ValidRecipient {
    String message() default "Informe ao menos um destinatário";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
