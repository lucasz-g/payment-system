"use client";

import Link from "next/link";
import styles from "./PaymentForm.module.css";
import SlideCommit from "../SlideCommit/SlideCommit";
import { ChangeEvent, useState } from "react";
import PaymentField from "../PaymentField/PaymentField";
import { OrderRequest } from "@/types/payment"; 
import api from '../../api/api'

const PaymentForm = () => {
  const [payerName, setPayerName] = useState("");
  const [amount, setAmount] = useState(0.0);
  const [receiver, setReceiver] = useState("");
  const [isEmail, setIsEmail] = useState(false);
  const [receiverError, setReceiverError] = useState("");
  const [description, setDescription] = useState("");

  async function postOrder(order: OrderRequest): Promise<OrderRequest> {
    const response = await api.post<OrderRequest>("/create", order); 
    return response.data; 
  }
  
  const handleReceiverChange = (
    e: ChangeEvent<HTMLInputElement, HTMLInputElement>,
  ) => {
    setReceiver(e.target.value);
    setReceiverError(""); 
  };

  const validateReceiver = () => {
    const emailRegex = /^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$/;
    const accountRegex = /^\d{8}-\d$/;
    if (
      receiver &&
      !emailRegex.test(receiver) &&
      !accountRegex.test(receiver)
    ) {
      console.log("E-mail inválido");
      setReceiverError("Informe ao menos um destinatário");
    }
    const isEmail = emailRegex.test(receiver);
    setIsEmail(isEmail); 
    console.log(`Destinatário: ${receiver}`);
  }; 

  const handleSubmit = () => {
    if (!payerName || !amount || !receiver) {
      throw new Error("Preencha todos os campos obrigatórios");
    }

    const order: OrderRequest = {
      payerName,
      amount,
      // Se receiver igual a um e-mail, atribui receiver ao campo receiverEmail
      // e receiverAccountNumber como null
      receiverEmail: isEmail ? receiver : null,  
      receiverAccountNumber: isEmail ? null : receiver, 
      description 
    }
    console.log(order);
    postOrder(order); 
  };

  return (
    <form className={styles.form}>
      <PaymentField
        id="payerName"
        label="Nome do pagador"
        onChange={(e) => setPayerName(e.target.value)}
      />
      <PaymentField
        id="amount"
        label="Valor (R$)"
        placeholder="0,00"
        variant="money"
        type="number"
        onChange={(e) => setAmount(e.target.valueAsNumber)}
      />
      <PaymentField
        id="receiver"
        label="E-mail ou nº da conta do destinatário"
        placeholder="destinatario@email.com ou 00012345-6"
        span="A notificação por e-mail é enviada automaticamente com base no cadastro do destinatário"
        onChange={handleReceiverChange}
        onBlur={validateReceiver}
      />
      <PaymentField
        id="description"
        label="Descrição (opcional)"
        placeholder="Pagamento de fatura #4521"
        onChange={(e) => setDescription(e.target.value)}
      />

      <div className={styles.actions}>
        <SlideCommit
          label="Deslize para pagar"
          doneLabel="Pago"
          errorLabel="Falha no pagamento"
          onConfirm={handleSubmit}
          onError={(reason) => console.error(reason)}
          trackColor="#0f766e"
          handleColor="#219283"
          labelColor="#ffffff"
          errorTextColor="#fff"
          successColor="#1e8178"
          dangerColor="#dc2626"
        />
        <Link href="/payments" className={styles.secondary}>
          Ver pagamentos →
        </Link>
      </div>
    </form>
  );
};

export default PaymentForm;
