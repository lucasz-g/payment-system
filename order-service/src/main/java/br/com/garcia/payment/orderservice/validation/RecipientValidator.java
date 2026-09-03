package br.com.garcia.payment.orderservice.validation;

import br.com.garcia.payment.orderservice.dto.OrderRequest;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * ValidRecipientValidator
 */
public class RecipientValidator implements ConstraintValidator<ValidRecipient, OrderRequest> {
    @Override
    public boolean isValid(OrderRequest request, ConstraintValidatorContext context) {
        // Recebe um request e valida se o email ou número da conta existem.
        boolean hasEmail = request.receiverEmail() != null && !request.receiverEmail().isBlank();

        boolean hasAccountNumber = request.receiverAccountNumber() != null
                && !request.receiverAccountNumber().isBlank();

        return hasEmail || hasAccountNumber;
        // Retorna true se pelo menos um dos campos estiver preenchido.
    }
}
