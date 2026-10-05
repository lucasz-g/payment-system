import PaymentForm from "@/components/PaymentForm/PaymentForm";
import styles from "./page.module.css";

export default function Home() {
  return (
    <main className={styles.page}>
      <div className={styles.container}>
        <div className={styles.heading}>
          <p className={styles.text}>NOVO PEDIDO</p>
          <h1 className={styles.title}>Criar um pagamento</h1>
        </div>
        <section className={styles.form}>
          <PaymentForm />
        </section>
      </div>
    </main>
  );
}
