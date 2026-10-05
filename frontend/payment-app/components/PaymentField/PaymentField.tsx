import React from "react";
import styles from "./PaymentField.module.css";

// Todos os atributos nativos de <input> (type, placeholder, onChange...) + props próprias
type PaymentFieldProps = React.InputHTMLAttributes<HTMLInputElement> & {
  id: string;
  label: string;
  variant?: "money"; // variações de estilo definidas em PaymentField.module.css
  span?: string; 
};

// ...inputProps recolhe as props restantes (tudo exceto id, label e variant)
const PaymentField = ({
  id,
  label,
  variant,
  span, 
  ...inputProps
}: PaymentFieldProps) => {
  return (
    <div className={styles.field}>
      <label htmlFor={id} className={styles.label}>
        {label}
      </label>
      <input
        id={id}
        name={id}
        // classe padrão + classe da variante, buscada pelo nome em styles
        className={`${styles.input} ${variant ? styles[variant] : ""}`}
        // espalha as props restantes no input
        {...inputProps}
      />
      <span className={styles.span}>
        {span}
      </span>
    </div>
  );
};

export default PaymentField;
