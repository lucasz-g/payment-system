import axios from "axios";
import type { AxiosRequestConfig, AxiosResponse, AxiosError, AxiosInstance } from "axios";

const api : AxiosInstance = axios.create({
    baseURL: "http://localhost:8080/api/v1/orders/",
    headers: {
        "Content-Type": "application/json",
    },
}) 

export default api; 