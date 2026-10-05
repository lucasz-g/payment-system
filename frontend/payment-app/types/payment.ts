export interface OrderRequest {
  payerName: string;
  amount: number;
  receiverEmail: string | null;
  receiverAccountNumber: string | null;
  description: string;
}
